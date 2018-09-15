package com.samourai.whirlpool.client.mix.dialog;

import com.samourai.whirlpool.client.mix.transport.StompTransport;
import com.samourai.whirlpool.client.mix.transport.TransportListener;
import com.samourai.whirlpool.client.utils.ClientUtils;
import com.samourai.whirlpool.client.whirlpool.WhirlpoolClientConfig;
import com.samourai.whirlpool.protocol.WhirlpoolProtocol;
import com.samourai.whirlpool.protocol.websocket.WhirlpoolMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.MessageHandler;
import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.Map;

public class MixSession {
    // non-static logger to prefix it with stomp sessionId
    private Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private MixDialogListener listener;
    private WhirlpoolProtocol whirlpoolProtocol;
    private WhirlpoolClientConfig config;
    private String poolId;
    private StompTransport transport;

    // connect data
    private Long connectBeginTime;
    private String stompSessionId;

    // session data
    private MixDialog dialog;

    public MixSession(MixDialogListener listener, WhirlpoolProtocol whirlpoolProtocol, WhirlpoolClientConfig config, String poolId) {
        this.listener = listener;
        this.whirlpoolProtocol = whirlpoolProtocol;
        this.config = config;
        this.poolId = poolId;
        this.transport = new StompTransport(config.getStompClient(), computeTransportListener());
        resetDialog();
    }

    private void resetDialog() {
        this.dialog = new MixDialog(listener, transport, config);
    }

    public synchronized void connect() {
        if (connectBeginTime == null) {
            connectBeginTime = System.currentTimeMillis();
        }

        String wsUrl ="ws://" + config.getServer() + WhirlpoolProtocol.ENDPOINT_CONNECT;
        if (log.isDebugEnabled()) {
            log.debug("connecting to server: " + wsUrl);
        }

        // connect
        Map<String,String> connectHeaders = computeStompHeaders(null);
        transport.connect(wsUrl, connectHeaders);
    }

    private void setLogPrefix(String logPrefix) {
        dialog.setLogPrefix(logPrefix);
        log = ClientUtils.prefixLogger(log, logPrefix);
    }

    private void subscribe() {
        // subscribe to private queue first (to receive error responses)
        final String privateQueue = whirlpoolProtocol.SOCKET_SUBSCRIBE_USER_PRIVATE + whirlpoolProtocol.SOCKET_SUBSCRIBE_USER_REPLY;
        transport.subscribe(computeStompHeaders(privateQueue), new MessageHandler.Whole<Object>() {
            @Override
            public void onMessage(Object payload) {
                // should be a WhirlpoolMessage
                WhirlpoolMessage whirlpoolMessage = checkMessage(payload);
                if (whirlpoolMessage != null) {
                    dialog.onPrivateReceived(whirlpoolMessage);
                } else {
                    log.error("--> " + privateQueue + ": not a WhirlpoolMessage: " + ClientUtils.toJsonString(payload));
                    listener.exitOnProtocolError();
                }
            }
        }, new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String errorMessage) {
                log.error("--> " + privateQueue + ": subscribe error: " + errorMessage);
                listener.exitOnResponseError(errorMessage); // probably a version mismatch
                listener.exitOnProtocolError();
            }
        });

        // subscribe mixStatusNotifications
        transport.subscribe(computeStompHeaders(whirlpoolProtocol.SOCKET_SUBSCRIBE_QUEUE), new MessageHandler.Whole<Object>() {
            @Override
            public void onMessage(Object payload) {
                WhirlpoolMessage whirlpoolMessage = checkMessage(payload);
                if (whirlpoolMessage != null) {
                    dialog.onBroadcastReceived(whirlpoolMessage);
                } else {
                    log.error("--> " + whirlpoolProtocol.SOCKET_SUBSCRIBE_QUEUE + ": not a WhirlpoolMessage: " + ClientUtils.toJsonString(payload));
                    listener.exitOnProtocolError();
                }
            }
        }, new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String errorMessage) {
                log.error("--> " + whirlpoolProtocol.SOCKET_SUBSCRIBE_QUEUE + ": subscribe error: " + errorMessage);
                listener.exitOnResponseError(errorMessage); // probably a version mismatch
                listener.exitOnProtocolError();
            }
        });

        // will automatically receive mixStatus in response of subscription
        if (log.isDebugEnabled()) {
            log.debug("subscribed to server");
        }
    }

    private WhirlpoolMessage checkMessage(Object payload) {
        // should be WhirlpoolMessage
        Class payloadClass = payload.getClass();
        if (!WhirlpoolMessage.class.isAssignableFrom(payloadClass)) {
            log.error("Protocol error: unexpected message from server: " + ClientUtils.toJsonString(payloadClass));
            listener.exitOnProtocolError();
            return null;
        }

        WhirlpoolMessage whirlpoolMessage = (WhirlpoolMessage)payload;

        // reset dialog on new mixId
        if (whirlpoolMessage.mixId != null && dialog.getMixId() != null && !dialog.getMixId().equals(whirlpoolMessage.mixId)) { // whirlpoolMessage.mixId is null for ErrorResponse
            if (log.isDebugEnabled()) {
                log.debug("new mixId detected: " + whirlpoolMessage.mixId);
            }
            resetDialog();
            listener.onResetMix();
        }

        return (WhirlpoolMessage) payload;
    }

    public synchronized void disconnect() {
        if (log.isDebugEnabled()) {
            log.debug("Disconnecting...");
        }
        stompSessionId = null;
        connectBeginTime = null;
        transport.disconnect();
        if (log.isDebugEnabled()) {
            log.debug("Disconnected.");
        }
    }

    //

    private Map<String,String> computeStompHeaders(String destination) {
        Map<String,String> stompHeaders = new HashMap<>();
        stompHeaders.put(WhirlpoolProtocol.HEADER_POOL_ID, poolId);
        if (destination != null) {
            stompHeaders.put(StompTransport.HEADER_DESTINATION, destination);
        }
        return stompHeaders;
    }

    private TransportListener computeTransportListener() {
        return new TransportListener() {

            @Override
            public synchronized void onTransportConnected(String stompUsername) {
                if (log.isDebugEnabled()) {
                    long elapsedTime = (System.currentTimeMillis() - connectBeginTime)/1000;
                    log.debug("Connected in " + elapsedTime + "s");
                }
                connectBeginTime = null;

                setLogPrefix(stompUsername);
                if (log.isDebugEnabled()) {
                    log.debug("connected to server, stompUsername=" + stompUsername);
                }

                // start dialog with server
                subscribe();

                listener.onConnected();
            }

            @Override
            public synchronized void onTransportDisconnected(Throwable exception) {

                if (connectBeginTime != null) {
                    // we were trying connect
                    long elapsedTime = System.currentTimeMillis() - connectBeginTime;
                    if (elapsedTime > config.getReconnectUntil() * 1000) {
                        // retry time exceeded, aborting
                        log.info(" ! Failed to connect to server. Please check your connectivity or retry later.");
                        connectBeginTime = null;
                        listener.exitOnDisconnected();
                        return;
                    }

                    // wait delay before retrying
                    log.info(" ! connexion failed, retrying in "+config.getReconnectDelay()+"s");
                    try {
                        wait(config.getReconnectDelay() * 1000);
                    }
                    catch(Exception e) {
                        log.error("", e);
                    }
                } else {
                    // we just got disconnected
                    if (dialog.gotRegisterInputResponse()) {
                        log.error(" ! connexion lost, reconnecting for resuming joined mix...");
                        // keep current dialog
                    } else {
                        log.error(" ! connexion lost, reconnecting for a new mix...");
                        resetDialog();
                        listener.onResetMix();
                    }
                }

                // reconnect
                connect();
            }
        };
    }

    //
    protected StompTransport __getTransport() {
        return transport;
    }
}