package org.apache.hadoop.ozone.om.protocolPB;

import java.io.IOException;
import java.net.InetSocketAddress;

import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.io.retry.RetryPolicy;
import org.apache.hadoop.io.retry.RetryPolicy.RetryAction.RetryDecision;
import org.apache.hadoop.io.retry.RetryProxy;
import org.apache.hadoop.ipc.Client;
import org.apache.hadoop.ipc.ProtobufHelper;
import org.apache.hadoop.ipc.ProtobufRpcEngine;
import org.apache.hadoop.ipc.RPC;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.ozone.OmUtils;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.om.exceptions.OMLeaderNotReadyException;
import org.apache.hadoop.ozone.om.exceptions.OMNotLeaderException;
import org.apache.hadoop.ozone.om.ha.OMFailoverProxyProvider;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.SecretManager;

import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Hadoop32RpcOmTransport implements OmTransport {

  /**
   * RpcController is not used and hence is set to null.
   */
  private static final RpcController NULL_RPC_CONTROLLER = null;

  private static final Logger FAILOVER_PROXY_PROVIDER_LOG =
      LoggerFactory.getLogger(OMFailoverProxyProvider.class);

  private final OMFailoverProxyProvider omFailoverProxyProvider;

  private final OzoneManagerProtocolPB rpcProxy;

  public Hadoop32RpcOmTransport(ConfigurationSource conf,
      UserGroupInformation ugi, String omServiceId) throws IOException {

    long omVersion = RPC.getProtocolVersion(OzoneManagerProtocolPB.class);
    InetSocketAddress omAddress = OmUtils.getOmAddressForClients(conf);
    RPC.setProtocolEngine(OzoneConfiguration.of(conf),
        OzoneManagerProtocolPB.class,
        ProtobufRpcEngine.class);

    OzoneManagerProtocolPB proxy =
        RPC.getProxy(OzoneManagerProtocolPB.class, omVersion, omAddress,
            ugi, OzoneConfiguration.of(conf),
            NetUtils.getDefaultSocketFactory(OzoneConfiguration.of(conf)),
            Client.getRpcTimeout(OzoneConfiguration.of(conf)));

    this.omFailoverProxyProvider = new OMFailoverProxyProvider(conf, ugi,
        omServiceId);

    int maxFailovers = conf.getInt(
        OzoneConfigKeys.OZONE_CLIENT_FAILOVER_MAX_ATTEMPTS_KEY,
        OzoneConfigKeys.OZONE_CLIENT_FAILOVER_MAX_ATTEMPTS_DEFAULT);

    this.rpcProxy = createRetryProxy(omFailoverProxyProvider, maxFailovers);
  }

  @Override
  public OMResponse submitRequest(OMRequest payload) throws IOException {
    try {
      OMResponse omResponse =
          rpcProxy.submitRequest(NULL_RPC_CONTROLLER, payload);

      if (omResponse.hasLeaderOMNodeId() && omFailoverProxyProvider != null) {
        String leaderOmId = omResponse.getLeaderOMNodeId();

        // Failover to the OM node returned by OMResponse leaderOMNodeId if
        // current proxy is not pointing to that node.
        omFailoverProxyProvider.performFailoverIfRequired(leaderOmId);
      }
      return omResponse;
    } catch (ServiceException e) {
      OMNotLeaderException notLeaderException = getNotLeaderException(e);
      if (notLeaderException == null) {
        throw ProtobufHelper.getRemoteException(e);
      }
      throw new IOException("Could not determine or connect to OM Leader.");
    }
  }

  /**
   * Creates a {@link RetryProxy} encapsulating the
   * {@link OMFailoverProxyProvider}. The retry proxy fails over on network
   * exception or if the current proxy is not the leader OM.
   */
  private OzoneManagerProtocolPB createRetryProxy(
      OMFailoverProxyProvider failoverProxyProvider, int maxFailovers) {

    // Client attempts contacting each OM ipc.client.connect.max.retries
    // (default = 10) times before failing over to the next OM, if
    // available.
    // Client will attempt upto maxFailovers number of failovers between
    // available OMs before throwing exception.
    RetryPolicy retryPolicy = new RetryPolicy() {
      @Override
      public RetryAction shouldRetry(Exception exception, int retries,
          int failovers, boolean isIdempotentOrAtMostOnce)
          throws Exception {
        if (isAccessControlException(exception)) {
          return RetryAction.FAIL; // do not retry
        }
        if (exception instanceof ServiceException) {
          OMNotLeaderException notLeaderException =
              getNotLeaderException(exception);
          if (notLeaderException != null &&
              notLeaderException.getSuggestedLeaderNodeId() != null) {
            FAILOVER_PROXY_PROVIDER_LOG.info("RetryProxy: {}",
                notLeaderException.getMessage());

            // TODO: NotLeaderException should include the host
            //  address of the suggested leader along with the nodeID.
            //  Failing over just based on nodeID is not very robust.

            // OMFailoverProxyProvider#performFailover() is a dummy call and
            // does not perform any failover. Failover manually to the next OM.
            omFailoverProxyProvider.performFailoverToNextProxy();
            return getRetryAction(RetryDecision.FAILOVER_AND_RETRY, failovers);
          }

          OMLeaderNotReadyException leaderNotReadyException =
              getLeaderNotReadyException(exception);
          // As in this case, current OM node is leader, but it is not ready.
          // OMFailoverProxyProvider#performFailover() is a dummy call and
          // does not perform any failover.
          // So Just retry with same OM node.
          if (leaderNotReadyException != null) {
            FAILOVER_PROXY_PROVIDER_LOG.info("RetryProxy: {}",
                leaderNotReadyException.getMessage());
            return getRetryAction(RetryDecision.FAILOVER_AND_RETRY, failovers);
          }
        }

        // For all other exceptions other than LeaderNotReadyException and
        // NotLeaderException fail over manually to the next OM Node proxy.
        // OMFailoverProxyProvider#performFailover() is a dummy call and
        // does not perform any failover.
        String exceptionMsg;
        if (exception.getCause() != null) {
          exceptionMsg = exception.getCause().getMessage();
        } else {
          exceptionMsg = exception.getMessage();
        }
        FAILOVER_PROXY_PROVIDER_LOG.info("RetryProxy: {}", exceptionMsg);
        omFailoverProxyProvider.performFailoverToNextProxy();
        return getRetryAction(RetryDecision.FAILOVER_AND_RETRY, failovers);
      }

      private RetryAction getRetryAction(RetryDecision fallbackAction,
          int failovers) {
        if (failovers < maxFailovers) {
          return new RetryAction(fallbackAction,
              omFailoverProxyProvider.getWaitTime());
        } else {
          FAILOVER_PROXY_PROVIDER_LOG.error("Failed to connect to OMs: {}. " +
                  "Attempted {} failovers.",
              omFailoverProxyProvider.getOMProxyInfos(), maxFailovers);
          return RetryAction.FAIL;
        }
      }
    };

    OzoneManagerProtocolPB proxy = (OzoneManagerProtocolPB) RetryProxy.create(
        OzoneManagerProtocolPB.class, failoverProxyProvider, retryPolicy);
    return proxy;
  }

  /**
   * Check if exception is OMLeaderNotReadyException.
   *
   * @param exception
   * @return OMLeaderNotReadyException
   */
  private OMLeaderNotReadyException getLeaderNotReadyException(
      Exception exception) {
    Throwable cause = exception.getCause();
    if (cause instanceof RemoteException) {
      IOException ioException =
          ((RemoteException) cause).unwrapRemoteException();
      if (ioException instanceof OMLeaderNotReadyException) {
        return (OMLeaderNotReadyException) ioException;
      }
    }
    return null;
  }

  /**
   * Unwrap exception to check if it is some kind of access control problem
   * ({@link AccessControlException} or {@link SecretManager.InvalidToken}).
   */
  private boolean isAccessControlException(Exception ex) {
    if (ex instanceof ServiceException) {
      Throwable t = ex.getCause();
      if (t instanceof RemoteException) {
        t = ((RemoteException) t).unwrapRemoteException();
      }
      while (t != null) {
        if (t instanceof AccessControlException ||
            t instanceof SecretManager.InvalidToken) {
          return true;
        }
        t = t.getCause();
      }
    }
    return false;
  }

  /**
   * Check if exception is a OMNotLeaderException.
   *
   * @return OMNotLeaderException.
   */
  private OMNotLeaderException getNotLeaderException(Exception exception) {
    Throwable cause = exception.getCause();
    if (cause instanceof RemoteException) {
      IOException ioException =
          ((RemoteException) cause).unwrapRemoteException();
      if (ioException instanceof OMNotLeaderException) {
        return (OMNotLeaderException) ioException;
      }
    }
    return null;
  }

}
