package de.pixart.messenger.entities;

import android.content.ContentValues;
import android.database.Cursor;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;

import net.java.otr4j.OtrException;
import net.java.otr4j.crypto.OtrCryptoException;
import net.java.otr4j.session.SessionID;
import net.java.otr4j.session.SessionImpl;
import net.java.otr4j.session.SessionStatus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.interfaces.DSAPublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.pixart.messenger.Config;
import de.pixart.messenger.crypto.OmemoSetting;
import de.pixart.messenger.crypto.PgpDecryptionService;
import de.pixart.messenger.crypto.axolotl.AxolotlService;
import de.pixart.messenger.utils.JidHelper;
import de.pixart.messenger.xmpp.chatstate.ChatState;
import de.pixart.messenger.xmpp.mam.MamReference;
import rocks.xmpp.addr.Jid;

import static de.pixart.messenger.entities.Bookmark.printableValue;


public class Conversation extends AbstractEntity implements Blockable, Comparable<Conversation>, Conversational {
    public static final String TABLENAME = "conversations";

    public static final int STATUS_AVAILABLE = 0;
    public static final int STATUS_ARCHIVED = 1;

    public static final String NAME = "name";
    public static final String ACCOUNT = "accountUuid";
    public static final String CONTACT = "contactUuid";
    public static final String CONTACTJID = "contactJid";
    public static final String STATUS = "status";
    public static final String CREATED = "created";
    public static final String MODE = "mode";
    public static final String ATTRIBUTES = "attributes";

    public static final String ATTRIBUTE_MUTED_TILL = "muted_till";
    public static final String ATTRIBUTE_ALWAYS_NOTIFY = "always_notify";
    public static final String ATTRIBUTE_LAST_CLEAR_HISTORY = "last_clear_history";
    static final String ATTRIBUTE_MUC_PASSWORD = "muc_password";
    private static final String ATTRIBUTE_NEXT_MESSAGE = "next_message";
    private static final String ATTRIBUTE_NEXT_MESSAGE_TIMESTAMP = "next_message_timestamp";
    private static final String ATTRIBUTE_CRYPTO_TARGETS = "crypto_targets";
    private static final String ATTRIBUTE_NEXT_ENCRYPTION = "next_encryption";
    public static final String ATTRIBUTE_MEMBERS_ONLY = "members_only";
    public static final String ATTRIBUTE_MODERATED = "moderated";
    public static final String ATTRIBUTE_NON_ANONYMOUS = "non_anonymous";
    protected final ArrayList<Message> messages = new ArrayList<>();
    public AtomicBoolean messagesLoaded = new AtomicBoolean(true);
    protected Account account = null;
    private String draftMessage;
    private String name;
    private String contactUuid;
    private String accountUuid;
    private Jid contactJid;
    private int status;
    private long created;
    private int mode;
    private JSONObject attributes = new JSONObject();
    private Jid nextCounterpart;
    private transient SessionImpl otrSession;
    private transient String otrFingerprint = null;
    private Smp mSmp = new Smp();
    private transient MucOptions mucOptions = null;
    private byte[] symmetricKey;
    private boolean messagesLeftOnServer = true;
    private ChatState mOutgoingChatState = Config.DEFAULT_CHATSTATE;
    private ChatState mIncomingChatState = Config.DEFAULT_CHATSTATE;
    private String mLastReceivedOtrMessageId = null;
    private String mFirstMamReference = null;
    private Message correctingMessage;

    public Conversation(final String name, final Account account, final Jid contactJid,
                        final int mode) {
        this(java.util.UUID.randomUUID().toString(), name, null, account
                        .getUuid(), contactJid, System.currentTimeMillis(),
                STATUS_AVAILABLE, mode, "");
        this.account = account;
    }

    public Conversation(final String uuid, final String name, final String contactUuid,
                        final String accountUuid, final Jid contactJid, final long created, final int status,
                        final int mode, final String attributes) {
        this.uuid = uuid;
        this.name = name;
        this.contactUuid = contactUuid;
        this.accountUuid = accountUuid;
        this.contactJid = contactJid;
        this.created = created;
        this.status = status;
        this.mode = mode;
        try {
            this.attributes = new JSONObject(attributes == null ? "" : attributes);
        } catch (JSONException e) {
            this.attributes = new JSONObject();
        }
    }

    public static Conversation fromCursor(Cursor cursor) {
        return new Conversation(cursor.getString(cursor.getColumnIndex(UUID)),
                cursor.getString(cursor.getColumnIndex(NAME)),
                cursor.getString(cursor.getColumnIndex(CONTACT)),
                cursor.getString(cursor.getColumnIndex(ACCOUNT)),
                JidHelper.parseOrFallbackToInvalid(cursor.getString(cursor.getColumnIndex(CONTACTJID))),
                cursor.getLong(cursor.getColumnIndex(CREATED)),
                cursor.getInt(cursor.getColumnIndex(STATUS)),
                cursor.getInt(cursor.getColumnIndex(MODE)),
                cursor.getString(cursor.getColumnIndex(ATTRIBUTES)));
    }

    public boolean hasMessagesLeftOnServer() {
        return messagesLeftOnServer;
    }

    public void setHasMessagesLeftOnServer(boolean value) {
        this.messagesLeftOnServer = value;
    }

    public void deleteMessage(Message message) {
        this.messages.remove(message);
    }

    public Message getFirstUnreadMessage() {
        Message first = null;
        synchronized (this.messages) {
            for (int i = messages.size() - 1; i >= 0; --i) {
                if (messages.get(i).isRead()) {
                    return first;
                } else {
                    first = messages.get(i);
                }
            }
        }
        return first;
    }

    public Message findUnsentMessageWithUuid(String uuid) {
        synchronized (this.messages) {
            for (final Message message : this.messages) {
                final int s = message.getStatus();
                if ((s == Message.STATUS_UNSEND || s == Message.STATUS_WAITING) && message.getUuid().equals(uuid)) {
                    return message;
                }
            }
        }
        return null;
    }

    public void findWaitingMessages(OnMessageFound onMessageFound) {
        final ArrayList<Message> results = new ArrayList<>();
        synchronized (this.messages) {
            for (Message message : this.messages) {
                if (message.getStatus() == Message.STATUS_WAITING) {
                    results.add(message);
                }
            }
        }
        for (Message result : results) {
            onMessageFound.onMessageFound(result);
        }
    }

    public void findUnreadMessages(OnMessageFound onMessageFound) {
        final ArrayList<Message> results = new ArrayList<>();
        synchronized (this.messages) {
            for (Message message : this.messages) {
                if (!message.isRead()) {
                    results.add(message);
                }
            }
        }
        for (Message result : results) {
            onMessageFound.onMessageFound(result);
        }
    }

    public void findMessagesWithFiles(final OnMessageFound onMessageFound) {
        final ArrayList<Message> results = new ArrayList<>();
        synchronized (this.messages) {
            for (final Message m : this.messages) {
                if (m.isFileOrImage() && m.getEncryption() != Message.ENCRYPTION_PGP) {
                    results.add(m);
                }
            }
        }
        for (Message result : results) {
            onMessageFound.onMessageFound(result);
        }
    }

    public void findFailedMessagesWithFiles(final OnMessageFound onMessageFound) {
        final ArrayList<Message> results = new ArrayList<>();
        synchronized (this.messages) {
            for (final Message m : this.messages) {
                if (m.isFileOrImage() && m.getEncryption() != Message.ENCRYPTION_PGP) {
                    if (m.getStatus() == Message.STATUS_SEND_FAILED && !m.isDeleted() && m.needsUploading()) {
                        results.add(m);
                    }
                }
            }
        }
        for(Message result : results) {
            onMessageFound.onMessageFound(result);
        }
    }

    public void findDeletedMessages(final OnMessageFound onMessageFound) {
        final ArrayList<Message> results = new ArrayList<>();
        synchronized (this.messages) {
            for (final Message m : this.messages) {
                if (m.isDeleted()) {
                    results.add(m);
                }
            }
        }
        for (Message result : results) {
            onMessageFound.onMessageFound(result);
        }
    }

    public Message findMessageWithFileAndUuid(final String uuid) {
        synchronized (this.messages) {
            for (final Message message : this.messages) {
                if (message.getUuid().equals(uuid)
                        && message.getEncryption() != Message.ENCRYPTION_PGP
                        && (message.getType() == Message.TYPE_IMAGE || message.getType() == Message.TYPE_FILE || message.treatAsDownloadable())) {
                    return message;
                }
            }
        }
        return null;
    }

    public void clearMessages() {
        synchronized (this.messages) {
            this.messages.clear();
        }
    }

    public boolean setIncomingChatState(ChatState state) {
        if (this.mIncomingChatState == state) {
            return false;
        }
        this.mIncomingChatState = state;
        return true;
    }

    public ChatState getIncomingChatState() {
        return this.mIncomingChatState;
    }

    public boolean setOutgoingChatState(ChatState state) {
        if (mode == MODE_SINGLE && !getContact().isSelf() || (isPrivateAndNonAnonymous() && getNextCounterpart() == null)) {
            if (this.mOutgoingChatState != state) {
                this.mOutgoingChatState = state;
                return true;
            }
        }
        return false;
    }

    public ChatState getOutgoingChatState() {
        return this.mOutgoingChatState;
    }

    public void trim() {
        synchronized (this.messages) {
            final int size = messages.size();
            final int maxsize = Config.PAGE_SIZE * Config.MAX_NUM_PAGES;
            if (size > maxsize) {
                List<Message> discards = this.messages.subList(0, size - maxsize);
                final PgpDecryptionService pgpDecryptionService = account.getPgpDecryptionService();
                if (pgpDecryptionService != null) {
                    pgpDecryptionService.discard(discards);
                }
                discards.clear();
                untieMessages();
            }
        }
    }

    public void findUnsentMessagesWithEncryption(int encryptionType, OnMessageFound onMessageFound) {
        synchronized (this.messages) {
            for (Message message : this.messages) {
                if ((message.getStatus() == Message.STATUS_UNSEND || message.getStatus() == Message.STATUS_WAITING)
                        && (message.getEncryption() == encryptionType)) {
                    onMessageFound.onMessageFound(message);
                }
            }
        }
    }

    public void findUnsentTextMessages(OnMessageFound onMessageFound) {
        final ArrayList<Message> results = new ArrayList<>();
        synchronized (this.messages) {
            for (Message message : this.messages) {
                if (message.getType() != Message.TYPE_IMAGE && message.getStatus() == Message.STATUS_UNSEND) {
                    results.add(message);
                }
            }
        }
        for(Message result : results) {
            onMessageFound.onMessageFound(result);
        }
    }

    public Message findSentMessageWithUuidOrRemoteId(String id) {
        synchronized (this.messages) {
            for (Message message : this.messages) {
                if (id.equals(message.getUuid())
                        || (message.getStatus() >= Message.STATUS_SEND
                        && id.equals(message.getRemoteMsgId()))) {
                    return message;
                }
            }
        }
        return null;
    }

    public Message findMessageWithRemoteIdAndCounterpart(String id, Jid counterpart, boolean received, boolean carbon) {
        synchronized (this.messages) {
            for (int i = this.messages.size() - 1; i >= 0; --i) {
                Message message = messages.get(i);
                if (counterpart.equals(message.getCounterpart())
                        && ((message.getStatus() == Message.STATUS_RECEIVED) == received)
                        && (carbon == message.isCarbon() || received)) {
                    if (id.equals(message.getRemoteMsgId()) && !message.isFileOrImage() && !message.treatAsDownloadable()) {
                        return message;
                    } else {
                        return null;
                    }
                }
            }
        }
        return null;
    }

    public Message findSentMessageWithUuid(String id) {
        synchronized (this.messages) {
            for (Message message : this.messages) {
                if (id.equals(message.getUuid())) {
                    return message;
                }
            }
        }
        return null;
    }

    public Message findMessageWithRemoteId(String id, Jid counterpart) {
        synchronized (this.messages) {
            for (Message message : this.messages) {
                if (counterpart.equals(message.getCounterpart())
                        && (id.equals(message.getRemoteMsgId()) || id.equals(message.getUuid()))) {
                    return message;
                }
            }
        }
        return null;
    }

    public boolean hasMessageWithCounterpart(Jid counterpart) {
        synchronized (this.messages) {
            for (Message message : this.messages) {
                if (counterpart.equals(message.getCounterpart())) {
                    return true;
                }
            }
        }
        return false;
    }

    public void populateWithMessages(final List<Message> messages) {
        synchronized (this.messages) {
            messages.clear();
            messages.addAll(this.messages);
        }
        for (Iterator<Message> iterator = messages.iterator(); iterator.hasNext(); ) {
            if (iterator.next().wasMergedIntoPrevious()) {
                iterator.remove();
            }
        }
    }

    @Override
    public boolean isBlocked() {
        return getContact().isBlocked();
    }

    @Override
    public boolean isDomainBlocked() {
        return getContact().isDomainBlocked();
    }

    @Override
    public Jid getBlockedJid() {
        return getContact().getBlockedJid();
    }

    public String getLastReceivedOtrMessageId() {
        return this.mLastReceivedOtrMessageId;
    }

    public void setLastReceivedOtrMessageId(String id) {
        this.mLastReceivedOtrMessageId = id;
    }

    public int countMessages() {
        synchronized (this.messages) {
            return this.messages.size();
        }
    }

    public String getFirstMamReference() {
        return this.mFirstMamReference;
    }

    public void setFirstMamReference(String reference) {
        this.mFirstMamReference = reference;
    }

    public void setLastClearHistory(long time, String reference) {
        if (reference != null) {
            setAttribute(ATTRIBUTE_LAST_CLEAR_HISTORY, String.valueOf(time) + ":" + reference);
        } else {
            setAttribute(ATTRIBUTE_LAST_CLEAR_HISTORY, time);
        }
    }

    public MamReference getLastClearHistory() {
        return MamReference.fromAttribute(getAttribute(ATTRIBUTE_LAST_CLEAR_HISTORY));
    }

    public List<Jid> getAcceptedCryptoTargets() {
        if (mode == MODE_SINGLE) {
            return Collections.singletonList(getJid().asBareJid());
        } else {
            return getJidListAttribute(ATTRIBUTE_CRYPTO_TARGETS);
        }
    }

    public void setAcceptedCryptoTargets(List<Jid> acceptedTargets) {
        setAttribute(ATTRIBUTE_CRYPTO_TARGETS, acceptedTargets);
    }

    public boolean setCorrectingMessage(Message correctingMessage) {
        this.correctingMessage = correctingMessage;
        return correctingMessage == null && draftMessage != null;
    }

    public Message getCorrectingMessage() {
        return this.correctingMessage;
    }

    public boolean withSelf() {
        return getContact().isSelf();
    }

    @Override
    public int compareTo(@NonNull Conversation another) {
        return Long.compare(another.getSortableTime(), getSortableTime());
    }

    private long getSortableTime() {
        Draft draft = getDraft();
        long messageTime = getLatestMessage().getTimeSent();
        if (draft == null) {
            return messageTime;
        } else {
            return Math.max(messageTime, draft.getTimestamp());
        }
    }

    public String getDraftMessage() {
        return draftMessage;
    }

    public void setDraftMessage(String draftMessage) {
        this.draftMessage = draftMessage;
    }

    public boolean isRead() {
        return (this.messages.size() == 0) || this.messages.get(this.messages.size() - 1).isRead();
    }

    public List<Message> markRead(String upToUuid) {
        final List<Message> unread = new ArrayList<>();
        synchronized (this.messages) {
            for (Message message : this.messages) {
                if (!message.isRead()) {
                    message.markRead();
                    unread.add(message);
                }
                if (message.getUuid().equals(upToUuid)) {
                    return unread;
                }
            }
        }
        return unread;
    }

    public static Message getLatestMarkableMessage(final List<Message> messages, boolean isPrivateAndNonAnonymousMuc) {
        for (int i = messages.size() - 1; i >= 0; --i) {
            final Message message = messages.get(i);
            if (message.getStatus() <= Message.STATUS_RECEIVED
                    && (message.markable || isPrivateAndNonAnonymousMuc)
                    && message.getType() != Message.TYPE_PRIVATE) {
                return message;
            }
        }
        return null;
    }

    public Message getLatestMessage() {
        synchronized (this.messages) {
            if (this.messages.size() == 0) {
                Message message = new Message(this, "", Message.ENCRYPTION_NONE);
                message.setType(Message.TYPE_STATUS);
                message.setTime(Math.max(getCreated(), getLastClearHistory().getTimestamp()));
                return message;
            } else {
                return this.messages.get(this.messages.size() - 1);
            }
        }
    }

    public @NonNull CharSequence getName() {
        if (getMode() == MODE_MULTI) {
            final String roomName = getMucOptions().getName();
            final String subject = getMucOptions().getSubject();
            final Bookmark bookmark = getBookmark();
            final String bookmarkName = bookmark != null ? bookmark.getBookmarkName() : null;
            if (printableValue(roomName)) {
                return roomName;
            } else if (printableValue(subject)) {
                return subject;
            } else if (printableValue(bookmarkName, false)) {
                return bookmarkName;
            } else {
                final String generatedName = getMucOptions().createNameFromParticipants();
                if (printableValue(generatedName)) {
                    return generatedName;
                } else {
                    return contactJid.getLocal() != null ? contactJid.getLocal() : contactJid;
                }
            }
        } else if (isWithStranger()) {
            return contactJid;
        } else {
            return this.getContact().getDisplayName();
        }
    }

    public String getParticipants() {
        if (getMode() == MODE_MULTI) {
            String generatedName = getMucOptions().createNameFromParticipants();
            if (generatedName != null) {
                return generatedName;
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    public int getNumberOfParticipants() {
        if (getMode() == MODE_MULTI) {
            return getMucOptions().NumberOfUsers();
        } else {
            return 0;
        }
    }

    public String getAccountUuid() {
        return this.accountUuid;
    }

    public Account getAccount() {
        return this.account;
    }

    public void setAccount(final Account account) {
        this.account = account;
    }

    public Contact getContact() {
        return this.account.getRoster().getContact(this.contactJid);
    }

    @Override
    public Jid getJid() {
        return this.contactJid;
    }

    public int getStatus() {
        return this.status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public long getCreated() {
        return this.created;
    }

    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(UUID, uuid);
        values.put(NAME, name);
        values.put(CONTACT, contactUuid);
        values.put(ACCOUNT, accountUuid);
        values.put(CONTACTJID, contactJid.toString());
        values.put(CREATED, created);
        values.put(STATUS, status);
        values.put(MODE, mode);
        values.put(ATTRIBUTES, attributes.toString());
        return values;
    }

    public int getMode() {
        return this.mode;
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    public SessionImpl startOtrSession(String presence, boolean sendStart) {
        if (this.otrSession != null) {
            return this.otrSession;
        } else {
            final SessionID sessionId = new SessionID(this.getJid().asBareJid().toString(),
                    presence,
                    "xmpp");
            this.otrSession = new SessionImpl(sessionId, getAccount().getOtrService());
            try {
                if (sendStart) {
                    this.otrSession.startSession();
                    return this.otrSession;
                }
                return this.otrSession;
            } catch (OtrException e) {
                return null;
            }
        }

    }

    public SessionImpl getOtrSession() {
        return this.otrSession;
    }

    public void resetOtrSession() {
        this.otrFingerprint = null;
        this.otrSession = null;
        this.mSmp.hint = null;
        this.mSmp.secret = null;
        this.mSmp.status = Smp.STATUS_NONE;
    }

    public Smp smp() {
        return mSmp;
    }

    public boolean startOtrIfNeeded() {
        if (this.otrSession != null && this.otrSession.getSessionStatus() != SessionStatus.ENCRYPTED) {
            try {
                this.otrSession.startSession();
                return true;
            } catch (OtrException e) {
                this.resetOtrSession();
                return false;
            }
        } else {
            return true;
        }
    }

    public boolean endOtrIfNeeded() {
        if (this.otrSession != null) {
            if (this.otrSession.getSessionStatus() == SessionStatus.ENCRYPTED) {
                try {
                    this.otrSession.endSession();
                    this.resetOtrSession();
                    return true;
                } catch (OtrException e) {
                    this.resetOtrSession();
                    return false;
                }
            } else {
                this.resetOtrSession();
                return false;
            }
        } else {
            return false;
        }
    }

    public boolean hasValidOtrSession() {
        return this.otrSession != null;
    }

    public synchronized String getOtrFingerprint() {
        if (this.otrFingerprint == null) {
            try {
                if (getOtrSession() == null || getOtrSession().getSessionStatus() != SessionStatus.ENCRYPTED) {
                    return null;
                }
                DSAPublicKey remotePubKey = (DSAPublicKey) getOtrSession().getRemotePublicKey();
                this.otrFingerprint = getAccount().getOtrService().getFingerprint(remotePubKey).toLowerCase(Locale.US);
            } catch (final OtrCryptoException | UnsupportedOperationException ignored) {
                return null;
            }
        }
        return this.otrFingerprint;
    }

    public boolean verifyOtrFingerprint() {
        final String fingerprint = getOtrFingerprint();
        if (fingerprint != null) {
            getContact().addOtrFingerprint(fingerprint);
            return true;
        } else {
            return false;
        }
    }

    public boolean isOtrFingerprintVerified() {
        return getContact().getOtrFingerprints().contains(getOtrFingerprint());
    }

    /**
     * short for is Private and Non-anonymous
     */
    public boolean isSingleOrPrivateAndNonAnonymous() {
        return mode == MODE_SINGLE || isPrivateAndNonAnonymous();
    }

    public boolean isPrivateAndNonAnonymous() {
        return getMucOptions().isPrivateAndNonAnonymous();
    }

    public synchronized MucOptions getMucOptions() {
        if (this.mucOptions == null) {
            this.mucOptions = new MucOptions(this);
        }
        return this.mucOptions;
    }

    public void resetMucOptions() {
        this.mucOptions = null;
    }

    public void setContactJid(final Jid jid) {
        this.contactJid = jid;
    }

    public Jid getNextCounterpart() {
        return this.nextCounterpart;
    }

    public void setNextCounterpart(Jid jid) {
        this.nextCounterpart = jid;
    }

    public int getNextEncryption() {
        if (!Config.supportOmemo() && !Config.supportOpenPgp() && !Config.supportOtr()) {
            return Message.ENCRYPTION_NONE;
        }
        if (OmemoSetting.isAlways()) {
            return suitableForOmemoByDefault(this) ? Message.ENCRYPTION_AXOLOTL : Message.ENCRYPTION_NONE;
        }
        final int defaultEncryption;
        if (suitableForOmemoByDefault(this)) {
            defaultEncryption = OmemoSetting.getEncryption();
        } else {
            defaultEncryption = Message.ENCRYPTION_NONE;
        }
        int encryption = this.getIntAttribute(ATTRIBUTE_NEXT_ENCRYPTION, defaultEncryption);
        if (encryption < 0) {
            return defaultEncryption;
        } else if (encryption == Message.ENCRYPTION_OTR) {
            return encryption;
        } else {
            return encryption;
        }
    }


    private static boolean suitableForOmemoByDefault(final Conversation conversation) {
        if (conversation.getJid().asBareJid().equals(Config.BUG_REPORTS)) {
            return false;
        }
        if (conversation.getContact().isOwnServer()) {
            return false;
        }
        final String contact = conversation.getJid().getDomain();
        final String account = conversation.getAccount().getServer();
        if (Config.OMEMO_EXCEPTIONS.CONTACT_DOMAINS.contains(contact) || Config.OMEMO_EXCEPTIONS.ACCOUNT_DOMAINS.contains(account)) {
            return false;
        }
        final AxolotlService axolotlService = conversation.getAccount().getAxolotlService();
        return axolotlService != null && axolotlService.isConversationAxolotlCapable(conversation);
    }

    public void setNextEncryption(int encryption) {
        this.setAttribute(ATTRIBUTE_NEXT_ENCRYPTION, String.valueOf(encryption));
    }

    public String getNextMessage() {
        final String nextMessage = getAttribute(ATTRIBUTE_NEXT_MESSAGE);
        return nextMessage == null ? "" : nextMessage;
    }

    public boolean smpRequested() {
        return smp().status == Smp.STATUS_CONTACT_REQUESTED;
    }

    public @Nullable
    Draft getDraft() {
        long timestamp = getLongAttribute(ATTRIBUTE_NEXT_MESSAGE_TIMESTAMP, 0);
        if (timestamp > getLatestMessage().getTimeSent()) {
            String message = getAttribute(ATTRIBUTE_NEXT_MESSAGE);
            if (!TextUtils.isEmpty(message) && timestamp != 0) {
                return new Draft(message, timestamp);
            }
        }
        return null;
    }

    public boolean setNextMessage(final String input) {
        final String message = input == null || input.trim().isEmpty() ? null : input;
        boolean changed = !getNextMessage().equals(message);
        this.setAttribute(ATTRIBUTE_NEXT_MESSAGE, message);
        if (changed) {
            this.setAttribute(ATTRIBUTE_NEXT_MESSAGE_TIMESTAMP, message == null ? 0 : System.currentTimeMillis());
        }
        return changed;
    }

    public void setSymmetricKey(byte[] key) {
        this.symmetricKey = key;
    }

    public byte[] getSymmetricKey() {
        return this.symmetricKey;
    }

    public Bookmark getBookmark() {
        return this.account.getBookmark(this.contactJid);
    }

    public Message findDuplicateMessage(Message message) {
        synchronized (this.messages) {
            for (int i = this.messages.size() - 1; i >= 0; --i) {
                if (this.messages.get(i).similar(message)) {
                    return this.messages.get(i);
                }
            }
        }
        return null;
    }

    public boolean hasDuplicateMessage(Message message) {
        return findDuplicateMessage(message) != null;
    }

    public Message findSentMessageWithBody(String body) {
        synchronized (this.messages) {
            for (int i = this.messages.size() - 1; i >= 0; --i) {
                Message message = this.messages.get(i);
                if (message.getStatus() == Message.STATUS_UNSEND || message.getStatus() == Message.STATUS_SEND) {
                    String otherBody;
                    if (message.hasFileOnRemoteHost()) {
                        otherBody = message.getFileParams().url.toString();
                    } else {
                        otherBody = message.body;
                    }
                    if (otherBody != null && otherBody.equals(body)) {
                        return message;
                    }
                }
            }
            return null;
        }
    }

    public MamReference getLastMessageTransmitted() {
        final MamReference lastClear = getLastClearHistory();
        MamReference lastReceived = new MamReference(0);
        synchronized (this.messages) {
            for (int i = this.messages.size() - 1; i >= 0; --i) {
                final Message message = this.messages.get(i);
                if (message.getType() == Message.TYPE_PRIVATE) {
                    continue; //it's unsafe to use private messages as anchor. They could be coming from user archive
                }
                if (message.getStatus() == Message.STATUS_RECEIVED || message.isCarbon() || message.getServerMsgId() != null) {
                    lastReceived = new MamReference(message.getTimeSent(),message.getServerMsgId());
                    break;
                }
            }
        }
        return MamReference.max(lastClear,lastReceived);
    }

    public void setMutedTill(long value) {
        this.setAttribute(ATTRIBUTE_MUTED_TILL, String.valueOf(value));
    }

    public boolean isMuted() {
        return System.currentTimeMillis() < this.getLongAttribute(ATTRIBUTE_MUTED_TILL, 0);
    }

    public boolean alwaysNotify() {
        return mode == MODE_SINGLE || getBooleanAttribute(ATTRIBUTE_ALWAYS_NOTIFY, Config.ALWAYS_NOTIFY_BY_DEFAULT || isPrivateAndNonAnonymous());
    }

    public boolean setAttribute(String key, boolean value) {
        boolean prev = getBooleanAttribute(key, false);
        setAttribute(key, Boolean.toString(value));
        return prev != value;
    }

    private boolean setAttribute(String key, long value) {
        return setAttribute(key, Long.toString(value));
    }

    public boolean setAttribute(String key, String value) {
        synchronized (this.attributes) {
            try {
                if (value == null) {
                    if (this.attributes.has(key)) {
                        this.attributes.remove(key);
                        return true;
                    } else {
                        return false;
                    }
                } else {
                    final String prev = this.attributes.optString(key, null);
                    this.attributes.put(key, value);
                    return !value.equals(prev);
                }
            } catch (JSONException e) {
                throw new AssertionError(e);
            }
        }
    }

    public boolean setAttribute(String key, List<Jid> jids) {
        JSONArray array = new JSONArray();
        for (Jid jid : jids) {
            array.put(jid.asBareJid().toString());
        }
        synchronized (this.attributes) {
            try {
                this.attributes.put(key, array);
                return true;
            } catch (JSONException e) {
                return false;
            }
        }
    }

    public String getAttribute(String key) {
        synchronized (this.attributes) {
            return this.attributes.optString(key, null);
        }
    }

    private List<Jid> getJidListAttribute(String key) {
        ArrayList<Jid> list = new ArrayList<>();
        synchronized (this.attributes) {
            try {
                JSONArray array = this.attributes.getJSONArray(key);
                for (int i = 0; i < array.length(); ++i) {
                    try {
                        list.add(Jid.of(array.getString(i)));
                    } catch (IllegalArgumentException e) {
                        //ignored
                    }
                }
            } catch (JSONException e) {
                //ignored
            }
        }
        return list;
    }

    private int getIntAttribute(String key, int defaultValue) {
        String value = this.getAttribute(key);
        if (value == null) {
            return defaultValue;
        } else {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }

    public long getLongAttribute(String key, long defaultValue) {
        String value = this.getAttribute(key);
        if (value == null) {
            return defaultValue;
        } else {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
    }

    public boolean getBooleanAttribute(String key, boolean defaultValue) {
        String value = this.getAttribute(key);
        if (value == null) {
            return defaultValue;
        } else {
            return Boolean.parseBoolean(value);
        }
    }

    public void add(Message message) {
        synchronized (this.messages) {
            this.messages.add(message);
        }
    }

    public void prepend(int offset, Message message) {
        synchronized (this.messages) {
            this.messages.add(Math.min(offset, this.messages.size()), message);
        }
    }

    public void addAll(int index, List<Message> messages) {
        synchronized (this.messages) {
            this.messages.addAll(index, messages);
        }
        account.getPgpDecryptionService().decrypt(messages);
    }

    public void expireOldMessages(long timestamp) {
        synchronized (this.messages) {
            for (ListIterator<Message> iterator = this.messages.listIterator(); iterator.hasNext(); ) {
                if (iterator.next().getTimeSent() < timestamp) {
                    iterator.remove();
                }
            }
            untieMessages();
        }
    }

    public void sort() {
        synchronized (this.messages) {
            Collections.sort(this.messages, (left, right) -> {
                if (left.getTimeSent() < right.getTimeSent()) {
                    return -1;
                } else if (left.getTimeSent() > right.getTimeSent()) {
                    return 1;
                } else {
                    return 0;
                }
            });
            untieMessages();
        }
    }

    private void untieMessages() {
        for (Message message : this.messages) {
            message.untie();
        }
    }

    public int unreadCount() {
        synchronized (this.messages) {
            int count = 0;
            for (int i = this.messages.size() - 1; i >= 0; --i) {
                if (this.messages.get(i).isRead()) {
                    return count;
                }
                ++count;
            }
            return count;
        }
    }

    public int failedCount() {
        synchronized (this.messages) {
            int count = 0;
            for (int i = this.messages.size() - 1; i >= 0; --i) {
                Message message = this.messages.get(i);
                if ((message.getType() == Message.TYPE_IMAGE || message.getType() == Message.TYPE_FILE) && message.getEncryption() != Message.ENCRYPTION_PGP) {
                    if (message.getStatus() == Message.STATUS_SEND_FAILED && !message.isDeleted() && message.needsUploading()){
                        ++count;
                    }
                }
            }
            return count;
        }
    }

    public int receivedMessagesCount() {
        int count = 0;
        synchronized (this.messages) {
            for (Message message : messages) {
                if (message.getStatus() == Message.STATUS_RECEIVED) {
                    ++count;
                }
            }
        }
        return count;
    }

    public int sentMessagesCount() {
        int count = 0;
        synchronized (this.messages) {
            for (Message message : messages) {
                if (message.getStatus() != Message.STATUS_RECEIVED) {
                    ++count;
                }
            }
        }
        return count;
    }

    public boolean isWithStranger() {
        final Contact contact = getContact();
        return mode == MODE_SINGLE
                && !contact.isOwnServer()
                && !contact.showInContactList()
                && !contact.isSelf()
                && sentMessagesCount() == 0;
    }

    public int getReceivedMessagesCountSinceUuid(String uuid) {
        if (uuid == null) {
            return 0;
        }
        int count = 0;
        synchronized (this.messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                final Message message = messages.get(i);
                if (uuid.equals(message.getUuid())) {
                    return count;
                }
                if (message.getStatus() <= Message.STATUS_RECEIVED) {
                    ++count;
                }
            }
        }
        return 0;
    }

    public interface OnMessageFound {
        void onMessageFound(final Message message);
    }

    public static class Draft {
        private final String message;
        private final long timestamp;

        private Draft(String message, long timestamp) {
            this.message = message;
            this.timestamp = timestamp;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public String getMessage() {
            return message;
        }
    }

    public class Smp {
        public static final int STATUS_NONE = 0;
        public static final int STATUS_CONTACT_REQUESTED = 1;
        public static final int STATUS_WE_REQUESTED = 2;
        public static final int STATUS_FAILED = 3;
        public static final int STATUS_VERIFIED = 4;

        public String secret = null;
        public String hint = null;
        public int status = 0;
    }
}
