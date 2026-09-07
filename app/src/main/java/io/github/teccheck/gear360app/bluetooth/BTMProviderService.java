package io.github.teccheck.gear360app.bluetooth;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.samsung.android.sdk.accessory.SAAgentV2;
import com.samsung.android.sdk.accessory.SAPeerAccessory;
import com.samsung.android.sdk.accessory.SAPeerAgent;
import com.samsung.android.sdk.accessory.SASocket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class BTMProviderService extends SAAgentV2 {
    private static final String TAG = "LEGACY-SAP";
    private static final String TAG_RX = "LEGACY-RX";
    private static final String TAG_TX = "LEGACY-TX";
    private static final int PRIMARY_JSON_CHANNEL = 204;
    private static final int MAX_ALREADY_EXISTS_RETRIES = 2;
    private static final long ALREADY_EXISTS_RETRY_DELAY_MS = 1200L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private BTMProviderConnection providerConnection = null;
    private StatusCallback callback = null;
    private boolean peerDiscoveryRunning = false;
    private boolean serviceConnectionRequested = false;
    private int alreadyExistsRetries = 0;

    public BTMProviderService(Context context) {
        super(TAG, context, BTMProviderConnection.class);
        Log.i(TAG, "BTMProviderService created profile=" + serviceProfileSummary());
    }

    public void setup(StatusCallback callback) {
        Log.i(TAG, "setup profile=" + serviceProfileSummary());
        this.callback = callback;
    }

    public void findSaPeers() {
        Log.i(
            TAG,
            "findSaPeers socketConnected=" + isSocketConnected()
                + " peerDiscoveryRunning=" + peerDiscoveryRunning
                + " serviceConnectionRequested=" + serviceConnectionRequested
        );

        if (isSocketConnected()) {
            notifySapSocketConnected(providerConnection.getConnectedPeerAgent());
            return;
        }

        if (peerDiscoveryRunning) {
            Log.w(TAG, "SAP peer discovery already running");
            if (callback != null) {
                callback.onSapPeerDiscoveryDuplicate();
            }
            return;
        }

        peerDiscoveryRunning = true;
        if (callback != null) {
            callback.onSapDiscoveryStarted();
        }

        try {
            findPeerAgents();
        } catch (RuntimeException e) {
            peerDiscoveryRunning = false;
            Log.e(TAG, "findPeerAgents failed", e);
            if (callback != null) {
                callback.onSapPeerDiscoveryFailed(SAAgentV2.ERROR_FATAL, "findPeerAgents failed");
                callback.onError(SAAgentV2.ERROR_FATAL);
            }
        }
    }

    public boolean isSocketConnected() {
        return providerConnection != null && providerConnection.isConnected();
    }

    public void send(int channel, byte[] data) {
        if (!isSocketConnected()) {
            Log.w(TAG_TX, "channel=" + channel + " len=" + lengthOf(data) + " skipped=no_socket");
            if (callback != null) {
                callback.onError(SAAgentV2.ERROR_CONNECTION_INVALID_PARAM);
            }
            return;
        }

        try {
            Log.i(TAG_TX, "channel=" + channel + " len=" + lengthOf(data));
            if (channel == PRIMARY_JSON_CHANNEL) {
                Log.d(TAG_TX, "UTF8=" + utf8(data));
            }
            providerConnection.send(channel, data);
        } catch (IOException e) {
            Log.e(TAG, "send failed", e);
            if (callback != null) {
                callback.onError(SASocket.ERROR_FATAL);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "send failed", e);
            if (callback != null) {
                callback.onError(SAAgentV2.ERROR_FATAL);
            }
        }
    }

    public boolean closeConnection() {
        Log.i(TAG, "closeConnection socketConnected=" + isSocketConnected());

        peerDiscoveryRunning = false;
        serviceConnectionRequested = false;
        alreadyExistsRetries = 0;

        if (providerConnection == null) {
            releaseAgent();
            return false;
        }

        providerConnection.close();
        providerConnection = null;

        releaseAgent();
        return true;
    }

    @Override
    protected void onFindPeerAgentsResponse(SAPeerAgent[] peerAgents, int result) {
        peerDiscoveryRunning = false;
        Log.i(
            TAG,
            "onFindPeerAgentsResponse result=" + findPeerResultName(result)
                + "(" + result + ") peers=" + count(peerAgents)
        );

        if (result == SAAgentV2.PEER_AGENT_FOUND && peerAgents != null && peerAgents.length > 0) {
            for (SAPeerAgent peer : peerAgents) {
                logPeer(peer);
            }

            SAPeerAgent peer = selectPeer(peerAgents);
            if (peer != null) {
                Log.i(
                    TAG,
                    "PEER_AGENT_FOUND; provider role waits for incoming "
                        + SAAgentV2.ACTION_SERVICE_CONNECTION_REQUESTED
                );
                if (callback != null) {
                    callback.onSapPeerFound(
                        safeAccessoryName(peer),
                        safePeerId(peer),
                        safeProductId(peer)
                    );
                }
            }
            return;
        }

        if (result == SAAgentV2.FINDPEER_DEVICE_NOT_CONNECTED) {
            Log.e(TAG, "SAP: accessory transport not connected");
            notifyPeerDiscoveryFailed(result, "SAP accessory transport not connected");
        } else if (result == SAAgentV2.FINDPEER_SERVICE_NOT_FOUND) {
            Log.e(TAG, "SAP: Gear360 peer service not found");
            notifyPeerDiscoveryFailed(result, "SAP Gear360 peer service not found");
        } else if (result == SAAgentV2.FINDPEER_DUPLICATE_REQUEST) {
            Log.w(TAG, "SAP peer discovery duplicate request");
            if (callback != null) {
                callback.onSapPeerDiscoveryDuplicate();
            }
        } else {
            Log.e(TAG, "SAP peer discovery failed result=" + findPeerResultName(result));
            notifyPeerDiscoveryFailed(result, "SAP peer discovery failed: " + findPeerResultName(result));
        }
    }

    @Override
    protected void onPeerAgentsUpdated(SAPeerAgent[] peerAgents, int result) {
        Log.i(
            TAG,
            "onPeerAgentsUpdated result=" + peerUpdateResultName(result)
                + "(" + result + ") peers=" + count(peerAgents)
        );

        if (peerAgents != null) {
            for (SAPeerAgent peer : peerAgents) {
                logPeer(peer);
            }
        }

        if (result == SAAgentV2.PEER_AGENT_AVAILABLE) {
            SAPeerAgent peer = selectPeer(peerAgents);
            if (peer != null) {
                Log.i(
                    TAG,
                    "PEER_AGENT_AVAILABLE; provider role waits for incoming "
                        + SAAgentV2.ACTION_SERVICE_CONNECTION_REQUESTED
                );
                if (callback != null) {
                    callback.onSapPeerFound(
                        safeAccessoryName(peer),
                        safePeerId(peer),
                        safeProductId(peer)
                    );
                }
            }
        } else if (result == SAAgentV2.PEER_AGENT_UNAVAILABLE) {
            Log.w(TAG, "SAP peer unavailable; Gear 360 service disappeared");
            if (callback != null) {
                callback.onSapPeerUnavailable();
            }
        }
    }

    @Override
    protected void onServiceConnectionRequested(SAPeerAgent peerAgent) {
        Log.i(TAG, "INCOMING SERVICE CONNECTION REQUEST");
        logPeer(peerAgent);

        try {
            serviceConnectionRequested = true;
            if (callback != null) {
                callback.onSapConnectionRequested(
                    safeAccessoryName(peerAgent),
                    safePeerId(peerAgent),
                    safeProductId(peerAgent)
                );
            }
            Log.i(TAG, "Accepting incoming SAP service connection");
            acceptServiceConnectionRequest(peerAgent);
        } catch (RuntimeException e) {
            serviceConnectionRequested = false;
            Log.e(TAG, "acceptServiceConnectionRequest failed", e);
            if (callback != null) {
                callback.onError(SAAgentV2.ERROR_FATAL);
            }
        }
    }

    @Override
    protected void onError(SAPeerAgent peerAgent, String errorMessage, int errorCode) {
        super.onError(peerAgent, errorMessage, errorCode);
        peerDiscoveryRunning = false;
        serviceConnectionRequested = false;
        Log.e(
            TAG,
            "onError peer=" + peerSummary(peerAgent)
                + " message=" + errorMessage
                + " code=" + sapErrorName(errorCode) + "(" + errorCode + ")"
        );
        if (callback != null) {
            callback.onSapError(errorCode, errorMessage);
            callback.onError(errorCode);
        }
    }

    @Override
    protected void onServiceConnectionResponse(SAPeerAgent peerAgent, SASocket socket, int result) {
        Log.i(
            TAG,
            "onServiceConnectionResponse result=" + connectionResultName(result)
                + "(" + result + ") peer=" + peerSummary(peerAgent)
                + " socket=" + socket
        );

        if (result == SAAgentV2.CONNECTION_SUCCESS) {
            handleConnectionSuccess(peerAgent, socket);
        } else if (result == SAAgentV2.CONNECTION_ALREADY_EXIST) {
            handleConnectionAlreadyExists(peerAgent);
        } else if (result == SAAgentV2.CONNECTION_DUPLICATE_REQUEST) {
            Log.w(TAG, "SAP service connection duplicate request; waiting for response");
        } else {
            peerDiscoveryRunning = false;
            serviceConnectionRequested = false;
            Log.e(TAG, "SAP service connection failed result=" + connectionResultName(result));
            if (callback != null) {
                callback.onSapConnectionFailed(result, connectionResultName(result));
                callback.onError(result);
            }
        }
    }

    private void handleConnectionSuccess(SAPeerAgent peerAgent, SASocket socket) {
        peerDiscoveryRunning = false;
        serviceConnectionRequested = false;
        alreadyExistsRetries = 0;

        if (socket == null) {
            Log.e(TAG, "CONNECTION_SUCCESS with null SASocket");
            if (callback != null) {
                callback.onError(SAAgentV2.ERROR_FATAL);
            }
            return;
        }

        if (!(socket instanceof BTMProviderConnection)) {
            Log.e(TAG, "Unexpected SASocket type: " + socket.getClass().getName());
            socket.close();
            if (callback != null) {
                callback.onError(SAAgentV2.ERROR_FATAL);
            }
            return;
        }

        providerConnection = (BTMProviderConnection) socket;
        Log.i(TAG, "SASocket CONNECTED isConnected=" + providerConnection.isConnected());
        notifySapSocketConnected(peerAgent);
    }

    private void handleConnectionAlreadyExists(SAPeerAgent peerAgent) {
        peerDiscoveryRunning = false;
        serviceConnectionRequested = false;
        Log.w(
            TAG,
            "CONNECTION_ALREADY_EXIST providerConnection=" + providerConnection
                + " socketConnected=" + isSocketConnected()
                + " retries=" + alreadyExistsRetries
        );

        if (isSocketConnected()) {
            notifySapSocketConnected(peerAgent);
            return;
        }

        if (alreadyExistsRetries >= MAX_ALREADY_EXISTS_RETRIES) {
            Log.e(TAG, "SAP connection already exists but no usable SASocket was provided");
            if (callback != null) {
                callback.onSapConnectionFailed(
                    SAAgentV2.CONNECTION_ALREADY_EXIST,
                    "Connection already exists without usable SASocket"
                );
                callback.onError(SAAgentV2.CONNECTION_ALREADY_EXIST);
            }
            return;
        }

        alreadyExistsRetries++;
        mainHandler.postDelayed(
            new Runnable() {
                @Override
                public void run() {
                    Log.i(TAG, "Retrying SAP peer discovery after CONNECTION_ALREADY_EXIST");
                    findSaPeers();
                }
            },
            ALREADY_EXISTS_RETRY_DELAY_MS
        );
    }

    private void notifySapSocketConnected(SAPeerAgent peerAgent) {
        Log.i(
            TAG,
            "SASocket CONNECTED peer=" + peerSummary(peerAgent)
                + " profile=" + serviceProfileSummary()
        );
        if (callback != null) {
            callback.onSapSocketConnected(
                safeAccessoryName(peerAgent),
                safePeerId(peerAgent),
                safeProductId(peerAgent)
            );
            callback.onConnectDevice(
                safeAccessoryName(peerAgent),
                safePeerId(peerAgent),
                safeProductId(peerAgent)
            );
        }
    }

    private void notifyPeerDiscoveryFailed(int result, String message) {
        if (callback != null) {
            callback.onSapPeerDiscoveryFailed(result, message);
            callback.onError(result);
        }
    }

    private SAPeerAgent selectPeer(SAPeerAgent[] peerAgents) {
        if (peerAgents == null || peerAgents.length == 0) {
            return null;
        }

        for (SAPeerAgent peer : peerAgents) {
            String name = safeAccessoryName(peer);
            String productId = safeProductId(peer);
            String appName = safeString(new StringSupplier() {
                @Override
                public String get() {
                    return peer.getAppName();
                }
            });

            if (containsIgnoreCase(name, "Gear 360")
                || containsIgnoreCase(name, "Gear360")
                || containsIgnoreCase(productId, "Gear")
                || containsIgnoreCase(appName, "Gear")) {
                return peer;
            }
        }

        return peerAgents[0];
    }

    private void logPeer(SAPeerAgent peer) {
        if (peer == null) {
            Log.i(TAG, "SAP peer=null");
            return;
        }

        SAPeerAccessory accessory = safeAccessory(peer);
        Log.i(
            TAG,
            "SAP peer"
                + " peerId=" + safePeerId(peer)
                + " appName=" + safeString(new StringSupplier() {
                    @Override
                    public String get() {
                        return peer.getAppName();
                    }
                })
                + " profileVersion=" + safeString(new StringSupplier() {
                    @Override
                    public String get() {
                        return peer.getProfileVersion();
                    }
                })
                + " maxData=" + safeInt(new IntSupplier() {
                    @Override
                    public int get() {
                        return peer.getMaxAllowedDataSize();
                    }
                })
                + " maxMessage=" + safeInt(new IntSupplier() {
                    @Override
                    public int get() {
                        return peer.getMaxAllowedMessageSize();
                    }
                })
                + " accessoryName=" + safeAccessoryName(peer)
                + " accessoryAddress=" + safeAccessoryString(accessory, new AccessoryStringSupplier() {
                    @Override
                    public String get(SAPeerAccessory value) {
                        return value.getAddress();
                    }
                })
                + " productId=" + safeProductId(peer)
                + " vendorId=" + safeAccessoryString(accessory, new AccessoryStringSupplier() {
                    @Override
                    public String get(SAPeerAccessory value) {
                        return value.getVendorId();
                    }
                })
                + " transport=" + safeAccessoryInt(accessory, new AccessoryIntSupplier() {
                    @Override
                    public int get(SAPeerAccessory value) {
                        return value.getTransportType();
                    }
                })
                + " profile=" + serviceProfileSummary()
        );
    }

    private String serviceProfileSummary() {
        StringBuilder builder = new StringBuilder();
        builder.append("name=").append(safeString(new StringSupplier() {
            @Override
            public String get() {
                return getServiceProfileName();
            }
        }));
        builder.append(" id=").append(safeString(new StringSupplier() {
            @Override
            public String get() {
                return getServiceProfileId();
            }
        }));
        builder.append(" channels=").append(serviceChannelsSummary());
        return builder.toString();
    }

    private String serviceChannelsSummary() {
        try {
            StringBuilder builder = new StringBuilder("[");
            int count = getServiceChannelSize();
            for (int i = 0; i < count; i++) {
                if (i > 0) {
                    builder.append(",");
                }
                builder.append(getServiceChannelId(i));
            }
            builder.append("]");
            return builder.toString();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private String findPeerResultName(int result) {
        if (result == SAAgentV2.PEER_AGENT_FOUND) return "PEER_AGENT_FOUND";
        if (result == SAAgentV2.FINDPEER_DEVICE_NOT_CONNECTED) return "FINDPEER_DEVICE_NOT_CONNECTED";
        if (result == SAAgentV2.FINDPEER_SERVICE_NOT_FOUND) return "FINDPEER_SERVICE_NOT_FOUND";
        if (result == SAAgentV2.FINDPEER_DUPLICATE_REQUEST) return "FINDPEER_DUPLICATE_REQUEST";
        return sapErrorName(result);
    }

    private String peerUpdateResultName(int result) {
        if (result == SAAgentV2.PEER_AGENT_FOUND) return "PEER_AGENT_FOUND";
        if (result == SAAgentV2.PEER_AGENT_AVAILABLE) return "PEER_AGENT_AVAILABLE";
        if (result == SAAgentV2.PEER_AGENT_UNAVAILABLE) return "PEER_AGENT_UNAVAILABLE";
        return sapErrorName(result);
    }

    private String connectionResultName(int result) {
        if (result == SAAgentV2.CONNECTION_SUCCESS) return "CONNECTION_SUCCESS";
        if (result == SAAgentV2.CONNECTION_ALREADY_EXIST) return "CONNECTION_ALREADY_EXIST";
        if (result == SAAgentV2.CONNECTION_DUPLICATE_REQUEST) return "CONNECTION_DUPLICATE_REQUEST";
        if (result == SAAgentV2.CONNECTION_FAILURE_DEVICE_UNREACHABLE) return "CONNECTION_FAILURE_DEVICE_UNREACHABLE";
        if (result == SAAgentV2.CONNECTION_FAILURE_PEERAGENT_NO_RESPONSE) return "CONNECTION_FAILURE_PEERAGENT_NO_RESPONSE";
        if (result == SAAgentV2.CONNECTION_FAILURE_PEERAGENT_REJECTED) return "CONNECTION_FAILURE_PEERAGENT_REJECTED";
        if (result == SAAgentV2.CONNECTION_FAILURE_INVALID_PEERAGENT) return "CONNECTION_FAILURE_INVALID_PEERAGENT";
        if (result == SAAgentV2.CONNECTION_FAILURE_SERVICE_LIMIT_REACHED) return "CONNECTION_FAILURE_SERVICE_LIMIT_REACHED";
        if (result == SAAgentV2.CONNECTION_FAILURE_NETWORK) return "CONNECTION_FAILURE_NETWORK";
        return sapErrorName(result);
    }

    private String sapErrorName(int result) {
        if (result == SAAgentV2.FINDPEER_DEVICE_NOT_CONNECTED) return "FINDPEER_DEVICE_NOT_CONNECTED";
        if (result == SAAgentV2.FINDPEER_SERVICE_NOT_FOUND) return "FINDPEER_SERVICE_NOT_FOUND";
        if (result == SAAgentV2.FINDPEER_DUPLICATE_REQUEST) return "FINDPEER_DUPLICATE_REQUEST";
        if (result == SAAgentV2.CONNECTION_FAILURE_DEVICE_UNREACHABLE) return "CONNECTION_FAILURE_DEVICE_UNREACHABLE";
        if (result == SAAgentV2.CONNECTION_ALREADY_EXIST) return "CONNECTION_ALREADY_EXIST";
        if (result == SAAgentV2.CONNECTION_FAILURE_PEERAGENT_NO_RESPONSE) return "CONNECTION_FAILURE_PEERAGENT_NO_RESPONSE";
        if (result == SAAgentV2.CONNECTION_FAILURE_PEERAGENT_REJECTED) return "CONNECTION_FAILURE_PEERAGENT_REJECTED";
        if (result == SAAgentV2.CONNECTION_FAILURE_INVALID_PEERAGENT) return "CONNECTION_FAILURE_INVALID_PEERAGENT";
        if (result == SAAgentV2.CONNECTION_DUPLICATE_REQUEST) return "CONNECTION_DUPLICATE_REQUEST";
        if (result == SAAgentV2.CONNECTION_FAILURE_SERVICE_LIMIT_REACHED) return "CONNECTION_FAILURE_SERVICE_LIMIT_REACHED";
        if (result == SAAgentV2.CONNECTION_FAILURE_NETWORK) return "CONNECTION_FAILURE_NETWORK";
        if (result == SAAgentV2.ERROR_FATAL) return "ERROR_FATAL";
        if (result == SAAgentV2.ERROR_PERMISSION_DENIED) return "ERROR_PERMISSION_DENIED";
        if (result == SAAgentV2.ERROR_PERMISSION_FAILED) return "ERROR_PERMISSION_FAILED";
        if (result == SAAgentV2.ERROR_SDK_NOT_INITIALIZED) return "ERROR_SDK_NOT_INITIALIZED";
        return "UNKNOWN";
    }

    private String peerSummary(SAPeerAgent peer) {
        if (peer == null) {
            return "null";
        }
        return "peerId=" + safePeerId(peer)
            + " name=" + safeAccessoryName(peer)
            + " productId=" + safeProductId(peer);
    }

    private SAPeerAccessory safeAccessory(SAPeerAgent peer) {
        if (peer == null) {
            return null;
        }
        try {
            return peer.getAccessory();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String safePeerId(final SAPeerAgent peer) {
        return safeString(new StringSupplier() {
            @Override
            public String get() {
                return peer == null ? null : peer.getPeerId();
            }
        });
    }

    private String safeAccessoryName(SAPeerAgent peer) {
        SAPeerAccessory accessory = safeAccessory(peer);
        return safeAccessoryString(accessory, new AccessoryStringSupplier() {
            @Override
            public String get(SAPeerAccessory value) {
                return value.getName();
            }
        });
    }

    private String safeProductId(SAPeerAgent peer) {
        SAPeerAccessory accessory = safeAccessory(peer);
        return safeAccessoryString(accessory, new AccessoryStringSupplier() {
            @Override
            public String get(SAPeerAccessory value) {
                return value.getProductId();
            }
        });
    }

    private int count(SAPeerAgent[] peerAgents) {
        return peerAgents == null ? 0 : peerAgents.length;
    }

    private int lengthOf(byte[] data) {
        return data == null ? 0 : data.length;
    }

    private String utf8(byte[] data) {
        if (data == null) {
            return "";
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private boolean containsIgnoreCase(String value, String part) {
        return value != null && part != null && value.toLowerCase().contains(part.toLowerCase());
    }

    private String safeString(StringSupplier supplier) {
        try {
            String value = supplier.get();
            return value == null ? "" : value;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private int safeInt(IntSupplier supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private String safeAccessoryString(SAPeerAccessory accessory, AccessoryStringSupplier supplier) {
        if (accessory == null) {
            return "";
        }
        try {
            String value = supplier.get(accessory);
            return value == null ? "" : value;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private int safeAccessoryInt(SAPeerAccessory accessory, AccessoryIntSupplier supplier) {
        if (accessory == null) {
            return -1;
        }
        try {
            return supplier.get(accessory);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private interface StringSupplier {
        String get();
    }

    private interface IntSupplier {
        int get();
    }

    private interface AccessoryStringSupplier {
        String get(SAPeerAccessory accessory);
    }

    private interface AccessoryIntSupplier {
        int get(SAPeerAccessory accessory);
    }

    public class BTMProviderConnection extends SASocket {
        public BTMProviderConnection() {
            super(BTMProviderConnection.class.getName());
            Log.i(TAG, "BTMProviderConnection created");
        }

        @Override
        public void onReceive(int channelId, byte[] data) {
            Log.i(TAG_RX, "channel=" + channelId + " len=" + lengthOf(data));
            if (channelId == PRIMARY_JSON_CHANNEL) {
                Log.d(TAG_RX, "UTF8=" + utf8(data));
            }

            if (callback != null) {
                callback.onReceive(channelId, data);
            }
        }

        @Override
        public void onError(int channelId, String errorString, int error) {
            Log.e(TAG, "SASocket error channel=" + channelId + " error=" + error + " message=" + errorString);
            if (callback != null) {
                callback.onError(error);
            }
        }

        @Override
        public void onServiceConnectionLost(int errorCode) {
            Log.w(TAG, "SASocket connection lost error=" + errorCode);
            providerConnection = null;
            peerDiscoveryRunning = false;
            serviceConnectionRequested = false;
            alreadyExistsRetries = 0;
            if (callback != null) {
                callback.onServiceDisconnection();
            }
        }
    }

    public interface StatusCallback {
        void onConnectDevice(String name, String peer, String product);

        void onError(int result);

        void onReceive(int channelId, byte[] data);

        void onServiceDisconnection();

        default void onSapDiscoveryStarted() {}

        default void onSapPeerDiscoveryDuplicate() {}

        default void onSapPeerFound(String name, String peer, String product) {}

        default void onSapPeerUnavailable() {}

        default void onSapPeerDiscoveryFailed(int result, String message) {}

        default void onSapConnectionRequested(String name, String peer, String product) {}

        default void onSapConnectionFailed(int result, String message) {}

        default void onSapSocketConnected(String name, String peer, String product) {}

        default void onSapError(int result, String message) {}
    }
}
