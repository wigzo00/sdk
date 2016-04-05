
package wigzo.android.sdk;

import android.content.Context;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

/**
 * ConnectionQueue queues session and event data and periodically sends that data to
 * a wigzo server on a background thread.
 *
 * None of the methods in this class are synchronized because access to this class is
 * controlled by the Wigzo singleton, which is synchronized.
 *
 * NOTE: This class is only public to facilitate unit testing, because
 *       of this bug in dexmaker: https://code.google.com/p/dexmaker/issues/detail?id=34
 */
public class ConnectionQueue {
    private WigzoStore store_;
    private WigzoAppStore wigzoAppStore;
    private ExecutorService executor_;
    private String appKey_;
    private String orgId;
    private Context context_;
    private String serverURL_;
    private Future<?> connectionProcessorFuture_;
    private DeviceId deviceId_;
    private SSLContext sslContext_;

    // Getters are for unit testing
    String getAppKey() {
        return appKey_;
    }

    String getOrganizationId(){
        return orgId;
    }
    void setOrganizationId(String orgid){
        this.orgId = orgid;
    }
    void setAppKey(final String appKey) {
        appKey_ = appKey;
    }

    Context getContext() {
        return context_;
    }

    void setContext(final Context context) {
        context_ = context;
    }

    String getServerURL() {
        return serverURL_;
    }

    void setServerURL(final String serverURL) {
        serverURL_ = serverURL;

        if (Wigzo.publicKeyPinCertificates == null) {
            sslContext_ = null;
        } else {
            try {
                TrustManager tm[] = { new CertificateTrustManager(Wigzo.publicKeyPinCertificates) };
                sslContext_ = SSLContext.getInstance("TLS");
                sslContext_.init(null, tm, null);
            } catch (Throwable e) {
                throw new IllegalStateException(e);
            }
        }
    }

        WigzoStore getWigzoStore() {
        return store_;
    }

    WigzoAppStore getWigzoAppStore(){
        return wigzoAppStore;
    }

    void setWigzoStore(final WigzoStore wigzoStore) {
        store_ = wigzoStore;
    }
    void setWigzoAppStore(final WigzoAppStore wigzoStore) {
        wigzoAppStore = wigzoStore;
    }

    DeviceId getDeviceId() { return deviceId_; }

    public void setDeviceId(DeviceId deviceId) {
        this.deviceId_ = deviceId;
    }

    /**
     * Checks internal state and throws IllegalStateException if state is invalid to begin use.
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void checkInternalState() {
        if (context_ == null) {
            throw new IllegalStateException("context has not been set");
        }
        if(orgId == null || orgId.length() == 0){
            throw new IllegalArgumentException("Organization Id has not been set!");
        }
        if (appKey_ == null || appKey_.length() == 0) {
            throw new IllegalStateException("app key has not been set");
        }
        if (store_ == null) {
            throw new IllegalStateException("wigzo store has not been set");
        }
        if (wigzoAppStore == null) {
            throw new IllegalStateException("Mobile store has not been set");
        }
        if (serverURL_ == null || !Wigzo.isValidURL(serverURL_)) {
            throw new IllegalStateException("server URL is not valid");
        }
        if (Wigzo.publicKeyPinCertificates != null && !serverURL_.startsWith("https")) {
            throw new IllegalStateException("server must start with https once you specified public keys");
        }
    }

    /**
     * Records a session start event for the app and sends it to the server.
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void beginSession() {
        checkInternalState();
        final String data = "app_key=" + appKey_
                          + "&" +"orgId=" + orgId
                          + "&timestamp=" + Wigzo.currentTimestamp()
                          + "&hour=" + Wigzo.currentHour()
                          + "&dow=" + Wigzo.currentDayOfWeek()
                          + "&sdk_version=" + Wigzo.WIGZO_SDK_VERSION_STRING
                          + "&begin_session=1"
                          + "&metrics=" + DeviceInfo.getMetrics(context_);

        store_.addConnection(data);
        wigzoAppStore.addConnection(data);

        tick();
    }

    /**
     * Records a session duration event for the app and sends it to the server. This method does nothing
     * if passed a negative or zero duration.
     * @param duration duration in seconds to extend the current app session, should be more than zero
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void updateSession(final int duration) {
        checkInternalState();
        if (duration > 0) {
            final String data = "app_key=" + appKey_
                              + "&" +"orgId=" + orgId
                              + "&timestamp=" + Wigzo.currentTimestamp()
                              + "&hour=" + Wigzo.currentHour()
                              + "&dow=" + Wigzo.currentDayOfWeek()
                              + "&session_duration=" + duration
                              + "&location=" + getWigzoStore().getAndRemoveLocation();

            final String mobileData = "app_key=" + appKey_
                    + "&" +"orgId=" + orgId
                    + "&timestamp=" + Wigzo.currentTimestamp()
                    + "&hour=" + Wigzo.currentHour()
                    + "&dow=" + Wigzo.currentDayOfWeek()
                    + "&session_duration=" + duration
                    + "&location=" + getWigzoAppStore().getAndRemoveLocation();

            store_.addConnection(data);
            wigzoAppStore.addConnection(mobileData);

            tick();
        }
    }

    public void tokenSession(String token, Wigzo.WigzoMessagingMode mode) {
        checkInternalState();

        final String data = "app_key=" + appKey_
                + "&" +"orgId=" + orgId
                + "&" + "timestamp=" + Wigzo.currentTimestamp()
                + "&hour=" + Wigzo.currentHour()
                + "&dow=" + Wigzo.currentDayOfWeek()
                + "&" + "token_session=1"
                + "&" + "android_token=" + token
                + "&" + "test_mode=" + (mode == Wigzo.WigzoMessagingMode.TEST ? 2 : 0)
                + "&" + "locale=" + DeviceInfo.getLocale();

        // To ensure begin_session will be fully processed by the server before token_session
        final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
        worker.schedule(new Runnable() {
            @Override
            public void run() {
                store_.addConnection(data);
                wigzoAppStore.addConnection(data);
                tick();
            }
        }, 10, TimeUnit.SECONDS);
    }

    /**
     * Records a session end event for the app and sends it to the server. Duration is only included in
     * the session end event if it is more than zero.
     * @param duration duration in seconds to extend the current app session
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void endSession(final int duration) {
        checkInternalState();
        String data = "app_key=" + appKey_
                + "&" +"orgId=" + orgId
                + "&timestamp=" + Wigzo.currentTimestamp()
                    + "&hour=" + Wigzo.currentHour()
                    + "&dow=" + Wigzo.currentDayOfWeek()
                    + "&end_session=1";
        if (duration > 0) {
            data += "&session_duration=" + duration;
        }

        store_.addConnection(data);
        wigzoAppStore.addConnection(data);

        tick();
    }

    /**
     * Send user data to the server.
     * @throws java.lang.IllegalStateException if context, app key, store, or server URL have not been set
     */
    void sendUserData() {
        checkInternalState();
        String userdata = UserData.getDataForRequest();

        if(!userdata.equals("")){
            String data = "app_key=" + appKey_
                    + "&" +"orgId=" + orgId
                    + "&timestamp=" + Wigzo.currentTimestamp()
                    + "&hour=" + Wigzo.currentHour()
                    + "&dow=" + Wigzo.currentDayOfWeek()
                    + userdata;
            store_.addConnection(data);
            wigzoAppStore.addConnection(data);

            tick();
        }
    }

    /**
     * Attribute installation to Wigzo server.
     * @param referrer query parameters
     * @throws java.lang.IllegalStateException if context, app key, store, or server URL have not been set
     */
    void sendReferrerData(String referrer) {
        checkInternalState();

        if(referrer != null){
            String data = "app_key=" + appKey_
                    + "&" +"orgId=" + orgId
                    + "&timestamp=" + Wigzo.currentTimestamp()
                    + "&hour=" + Wigzo.currentHour()
                    + "&dow=" + Wigzo.currentDayOfWeek()
                    + referrer;
            store_.addConnection(data);
            wigzoAppStore.addConnection(data);

            tick();
        }
    }

    /**
     * Reports a crash with device data to the server.
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void sendCrashReport(String error, boolean nonfatal) {
        checkInternalState();
        final String data = "app_key=" + appKey_
                + "&" +"orgId=" + orgId
                + "&timestamp=" + Wigzo.currentTimestamp()
                + "&hour=" + Wigzo.currentHour()
                + "&dow=" + Wigzo.currentDayOfWeek()
                + "&sdk_version=" + Wigzo.WIGZO_SDK_VERSION_STRING
                + "&crash=" + CrashDetails.getCrashData(context_, error, nonfatal);

        store_.addConnection(data);
        wigzoAppStore.addConnection(data);

        tick();
    }

    /**
     * Records the specified events and sends them to the server.
     * @param events URL-encoded JSON string of event data
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void recordEvents(final String events) {
        checkInternalState();
        final String data = "app_key=" + appKey_
                + "&" +"orgId=" + orgId
                + "&timestamp=" + Wigzo.currentTimestamp()
                          + "&hour=" + Wigzo.currentHour()
                          + "&dow=" + Wigzo.currentDayOfWeek()
                          + "&events=" + events;

        store_.addConnection(data);
        wigzoAppStore.addConnection(data);

        tick();
    }

    /**
     * Records the specified events and sends them to the server.
     * @param events URL-encoded JSON string of event data
     * @throws IllegalStateException if context, app key, store, or server URL have not been set
     */
    void recordLocation(final String events) {
        checkInternalState();
        final String data = "app_key=" + appKey_
                + "&" +"orgId=" + orgId
                + "&timestamp=" + Wigzo.currentTimestamp()
                          + "&hour=" + Wigzo.currentHour()
                          + "&dow=" + Wigzo.currentDayOfWeek()
                          + "&events=" + events;

        store_.addConnection(data);
        wigzoAppStore.addConnection(data);

        tick();
    }

    /**
     * Ensures that an executor has been created for ConnectionProcessor instances to be submitted to.
     */
    void ensureExecutor() {
        if (executor_ == null) {
            executor_ = Executors.newSingleThreadExecutor();
        }
    }

    /**
     * Starts ConnectionProcessor instances running in the background to
     * process the local connection queue data.
     * Does nothing if there is connection queue data or if a ConnectionProcessor
     * is already running.
     */
    void tick() {
        if (!store_.isEmptyConnections() && (connectionProcessorFuture_ == null || connectionProcessorFuture_.isDone())) {
            ensureExecutor();
            connectionProcessorFuture_ = executor_.submit(new ConnectionProcessorWigzoApp(serverURL_, wigzoAppStore, deviceId_, sslContext_));
            connectionProcessorFuture_ = executor_.submit(new ConnectionProcessor(serverURL_, store_, deviceId_, sslContext_));

        }

    }

    // for unit testing
    ExecutorService getExecutor() { return executor_; }
    void setExecutor(final ExecutorService executor) { executor_ = executor; }
    Future<?> getConnectionProcessorFuture() { return connectionProcessorFuture_; }
    void setConnectionProcessorFuture(final Future<?> connectionProcessorFuture) { connectionProcessorFuture_ = connectionProcessorFuture; }

}
