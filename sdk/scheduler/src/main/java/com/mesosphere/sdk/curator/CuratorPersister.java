package com.mesosphere.sdk.curator;

import com.mesosphere.sdk.offer.LoggingUtils;
import com.mesosphere.sdk.specification.ServiceSpec;
import com.mesosphere.sdk.storage.Persister;
import com.mesosphere.sdk.storage.PersisterException;
import com.mesosphere.sdk.storage.PersisterUtils;
import com.mesosphere.sdk.storage.StorageError.Reason;

import com.google.common.annotations.VisibleForTesting;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.api.ACLProvider;
import org.apache.curator.framework.api.transaction.CuratorTransactionFinal;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.data.ACL;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The Curator implementation of the {@link Persister} interface provides for persistence and
 * retrieval of data from Zookeeper. All paths passed to this instance are automatically
 * namespaced within a framework-specific znode to avoid conflicts with other users of the same
 * ZK instance.
 */
public class CuratorPersister implements Persister {

  private static final Logger LOGGER = LoggingUtils.getLogger(CuratorPersister.class);

  /**
   * Number of times to attempt an atomic write transaction in storeMany().
   * This transaction should only fail if other parties are modifying the data.
   */
  private static final int ATOMIC_WRITE_ATTEMPTS = 3;

  private final String serviceRootPath;

  private final CuratorFramework client;

  @VisibleForTesting
  CuratorPersister(String serviceName, CuratorFramework client) {
    this.serviceRootPath = CuratorUtils.getServiceRootPath(serviceName);
    this.client = client;
    this.client.start();
  }

  /**
   * Creates a new {@link Builder} instance which has been initialized with reasonable default
   * values.
   *
   * @param serviceSpec a service specification containing the name and zookeeper connection info
   */
  public static Builder newBuilder(ServiceSpec serviceSpec) {
    return newBuilder(serviceSpec.getName(), serviceSpec.getZookeeperConnection());
  }

  /**
   * Creates a new {@link Builder} instance which has been initialized with reasonable default
   * values.
   *
   * @param serviceName       the name of the service, e.g. {@code /path/to/service}
   * @param zookeeperHostPort the {@code host:port} where zookeeper is located
   */
  public static Builder newBuilder(String serviceName, String zookeeperHostPort) {
    return new Builder(serviceName, zookeeperHostPort);
  }

  /**
   * Updates and returns a transaction which can be used to create missing parents of the provided
   * path, if any.
   */
  private static CuratorTransactionFinal createParentsOf(
      CuratorFramework client,
      String path,
      CuratorTransactionFinal curatorTransactionFinal,
      Set<String> existingAndPendingCreatePaths) throws Exception
  {
    for (String parentPath : PersisterUtils.getParentPaths(path)) {
      if (!existingAndPendingCreatePaths.contains(parentPath)
          && client.checkExists().forPath(parentPath) == null)
      {
        // SUPPRESS CHECKSTYLE ParameterAssignment
        curatorTransactionFinal = curatorTransactionFinal.create().forPath(parentPath).and();
      }
      existingAndPendingCreatePaths.add(parentPath);
    }
    return curatorTransactionFinal;
  }

  /**
   * Updates and returns a transaction which can be used to delete the children of the provided
   * path, if any.
   */
  private static CuratorTransactionFinal deleteChildrenOf(
      CuratorFramework client,
      String path,
      CuratorTransactionFinal curatorTransactionFinal,
      Set<String> pendingDeletePaths) throws Exception
  {
    if (pendingDeletePaths.contains(path)) {
      // Short-circuit: Path and any children are already scheduled for deletion
      return curatorTransactionFinal;
    }
    // For each child: recurse into child (to delete any grandchildren, etc..), THEN delete child
    // itself
    for (String child : client.getChildren().forPath(path)) {
      String childPath = PersisterUtils.joinPaths(path, child);
      // SUPPRESS CHECKSTYLE ParameterAssignment
      curatorTransactionFinal =
          deleteChildrenOf(client, childPath, curatorTransactionFinal, pendingDeletePaths);
      if (!pendingDeletePaths.contains(childPath)) {
        // Avoid attempting to delete a path twice in the same transaction, just in case we're told
        // to delete two nodes where one is the child of the other (or something to that effect)
        // SUPPRESS CHECKSTYLE ParameterAssignment
        curatorTransactionFinal = curatorTransactionFinal.delete().forPath(childPath).and();
        pendingDeletePaths.add(childPath);
      }
    }
    return curatorTransactionFinal;
  }

  private static String getInfo(byte[] bytes) {
    return bytes == null ? "NULL" : String.format("%d bytes", bytes.length);
  }

  @Override
  public byte[] get(String unprefixedPath) throws PersisterException {
    final String path = withFrameworkPrefix(unprefixedPath);
    try {
      return client.getData().forPath(path);
    } catch (KeeperException.NoNodeException e) {
      if (path.equals(serviceRootPath)) {
        // Special case: Root is always present. Missing root should be treated as a root
        // with no data.
        return null;
      }
      throw new PersisterException(
          Reason.NOT_FOUND,
          String.format("Path to get does not exist: %s", path), e);
    } catch (Exception e) { // SUPPRESS CHECKSTYLE IllegalCatch
      throw new PersisterException(
          Reason.STORAGE_ERROR,
          // SUPPRESS CHECKSTYLE MultipleStringLiterals
          String.format("Unable to retrieve data from %s", path), e);
    }
  }

  @Override
  public Collection<String> getChildren(String unprefixedPath) throws PersisterException {
    final String path = withFrameworkPrefix(unprefixedPath);
    try {
      return new TreeSet<>(client.getChildren().forPath(path));
    } catch (KeeperException.NoNodeException e) {
      if (path.equals(serviceRootPath)) {
        // Special case: Root is always present. Missing root should be treated as a root with
        // no data.
        return Collections.emptySet();
      }
      throw new PersisterException(
          Reason.NOT_FOUND,
          String.format("Path to list does not exist: %s", path), e);
    } catch (Exception e) { // SUPPRESS CHECKSTYLE IllegalCatch
      throw new PersisterException(
          Reason.STORAGE_ERROR,
          String.format("Unable to get children of %s", path), e);
    }
  }

  @Override
  public void set(String unprefixedPath, byte[] bytes) throws PersisterException {
    final String path = withFrameworkPrefix(unprefixedPath);
    LOGGER.debug("Setting {} => {}", path, getInfo(bytes));
    try {
      try {
        client.create().creatingParentsIfNeeded().forPath(path, bytes);
      } catch (KeeperException.NodeExistsException e) {
        client.setData().forPath(path, bytes);
      }
    } catch (Exception e) { // SUPPRESS CHECKSTYLE IllegalCatch
      throw new PersisterException(Reason.STORAGE_ERROR,
          String.format("Unable to set %d bytes in %s", bytes.length, path), e);
    }
  }

  @Override
  public Map<String, byte[]> getMany(Collection<String> unprefixedPaths) throws PersisterException {
    if (unprefixedPaths.isEmpty()) {
      return Collections.emptyMap();
    }
    LOGGER.debug("Getting {} entries: {}", unprefixedPaths.size(), unprefixedPaths);

    Map<String, byte[]> result = new TreeMap<>();
    // Unlike with writes, there is not an atomic read operation. Therefore we wing it with a series
    // of plain reads. We could conceivably add some form of locking here to avoid e.g. a race
    // with another thread doing writes at the same time, but assuming the PersisterCache is
    // enabled, this function wouldn't be getting called anyway, as the PersisterCache would have
    // fetched all the data up-front to be served from memory. If this assumption changes, then it
    // may make sense to look into some form of proper read locking here.
    for (String unprefixedPath : unprefixedPaths) {
      String path = withFrameworkPrefix(unprefixedPath);
      try {
        result.put(unprefixedPath, client.getData().forPath(path));
      } catch (KeeperException.NoNodeException e) {
        result.put(unprefixedPath, null);
      } catch (Exception e) { // SUPPRESS CHECKSTYLE IllegalCatch
        throw new PersisterException(Reason.STORAGE_ERROR,
            String.format("Unable to retrieve data from %s", path), e);
      }
    }
    return result;
  }

  @Override
  public void setMany(Map<String, byte[]> unprefixedPathBytesMap) throws PersisterException {
    if (!unprefixedPathBytesMap.isEmpty()) {
      // Convert map to translated prefixed paths. Manually construct TreeMap for consistent
      // ordering (for tests):
      Map<String, byte[]> pathBytesMap = new TreeMap<>();
      for (Map.Entry<String, byte[]> entry : unprefixedPathBytesMap.entrySet()) {
        pathBytesMap.put(withFrameworkPrefix(entry.getKey()), entry.getValue());
      }
      LOGGER.debug("Updating {} entries: {}", pathBytesMap.size(), pathBytesMap.keySet());
      runTransactionWithRetries(new SetTransactionFactory(pathBytesMap));
    }
  }

  @Override
  public void recursiveCopy(String unprefixedSrc, String unprefixedDest) throws PersisterException {
    if (Stream
        .of(serviceRootPath, CuratorLocker.LOCK_PATH_NAME)
        .anyMatch(x -> x.equals(unprefixedSrc) || x.equals(unprefixedDest)))
    {
      throw new IllegalArgumentException(String.format(
          "Cannot copy from %s to %s",
          unprefixedSrc,
          unprefixedDest));
    }
    try {
      LOGGER.debug("Copying {} (and any children) to {}", unprefixedSrc, unprefixedDest);
      if (client.checkExists().forPath(withFrameworkPrefix(unprefixedSrc)) == null) {
        throw new PersisterException(Reason.NOT_FOUND,
            String.format("Source node does not exist: %s", unprefixedSrc));
      }
      if (client.checkExists().forPath(withFrameworkPrefix(unprefixedDest)) != null) {
        throw new PersisterException(Reason.LOGIC_ERROR,
            String.format("Destination exists: %s", unprefixedDest));
      }
    } catch (Exception e) { // SUPPRESS CHECKSTYLE IllegalCatch
      throw new PersisterException(Reason.STORAGE_ERROR,
          String.format("Failed to copy %s to %s", unprefixedDest, unprefixedSrc), e);
    }
    LinkedList<String> toBeWalked = new LinkedList<>(Collections.singleton(unprefixedSrc));
    Map<String, byte[]> toBeAdded = new HashMap<>();
    while (!toBeWalked.isEmpty()) {
      String curNode = toBeWalked.poll();
      // This assertion should never fail acc. to the logic around it.
      assert curNode.startsWith(unprefixedSrc) : String.format(
          "Failed to match prefix. Src [%s] Dest [%s] Child [%s]",
          unprefixedSrc,
          unprefixedDest,
          curNode);
      toBeAdded.put(
          withFrameworkPrefix(curNode.replace(unprefixedSrc, unprefixedDest)),
          get(curNode)
      );
      getChildren(curNode)
          .forEach(child -> toBeWalked.add(PersisterUtils.joinPaths(curNode, child)));
    }
    runTransactionWithRetries(new SetTransactionFactory(toBeAdded));
  }

  @Override
  public void recursiveDelete(String unprefixedPath) throws PersisterException {
    final String path = withFrameworkPrefix(unprefixedPath);
    if (path.equals(serviceRootPath)) {
      // Special case: If we're being told to delete root, we should instead delete the contents
      // OF root. We don't have access to delete the root-level '/dcos-service-<svcname>' node
      // itself, despite having created it.
      /* Effective 5/8/2017:
       * We cannot delete the root node of a service directly.
       * This is due to the ACLs on the global DC/OS ZK.
       *
       * The root has:
       * [zk: localhost:2181(CONNECTED) 1] getAcl /
       * 'world,'anyone
       * : cr
       * 'ip,'127.0.0.1
       * : cdrwa
       *
       * Our service nodes have:
       * [zk: localhost:2181(CONNECTED) 0] getAcl /dcos-service-hello-world
       * 'world,'anyone
       * : cdrwa
       *
       * The best we can do is to wipe everything under the root node. A proposed way to "fix"
       * things lives at https://jira.mesosphere.com/browse/INFINITY-1470.
       */
      LOGGER.debug("Deleting children of root {}", path);
      try {
        CuratorTransactionFinal transaction = client
            .inTransaction()
            .check()
            .forPath(serviceRootPath)
            .and();
        Set<String> pendingDeletePaths = new HashSet<>();
        for (String child : client.getChildren().forPath(serviceRootPath)) {
          // Custom logic for root-level children: don't delete the lock node
          if (child.equals(CuratorLocker.LOCK_PATH_NAME)) {
            continue;
          }
          String childPath = PersisterUtils.joinPaths(serviceRootPath, child);
          transaction = deleteChildrenOf(client, childPath, transaction, pendingDeletePaths)
              .delete().forPath(childPath).and();
        }
        transaction.commit();
      } catch (Exception e) { // SUPPRESS CHECKSTYLE IllegalCatch
        throw new PersisterException(Reason.STORAGE_ERROR,
            String.format("Unable to delete children of root %s: %s", path, e.getMessage()), e);
      }
      // Need to explicitly set null or else curator will return a zero-bytes value later:
      set(unprefixedPath, null);
    } else {
      // Normal case: Delete node itself and any/all children.
      LOGGER.debug("Deleting {} (and any children)", path);
      try {
        client.delete().deletingChildrenIfNeeded().forPath(path);
      } catch (KeeperException.NoNodeException e) {
        throw new PersisterException(
            Reason.NOT_FOUND, String.format("Path to delete does not exist: %s", path), e);
      } catch (Exception e) { // SUPPRESS CHECKSTYLE IllegalCatch
        throw new PersisterException(
            Reason.STORAGE_ERROR,
            String.format("Unable to delete %s", path),
            e);
      }
    }
  }

  @Override
  public void recursiveDeleteMany(Collection<String> unprefixedPaths) throws PersisterException {
    if (!unprefixedPaths.isEmpty()) {
      Collection<String> paths = unprefixedPaths.stream()
          .map(this::withFrameworkPrefix)
          .collect(Collectors.toList());
      LOGGER.debug("Deleting {} entries: {}", paths.size(), paths);
      runTransactionWithRetries(new ClearTransactionFactory(paths));
    }
  }

  @Override
  public void close() {
    client.close();
  }

  private void runTransactionWithRetries(TransactionFactory factory) throws PersisterException {
    try {
      for (int i = 0; i < ATOMIC_WRITE_ATTEMPTS; ++i) {
        CuratorTransactionFinal transaction = factory.build(client, serviceRootPath);

        // Attempt to run the transaction, retrying if applicable:
        if (i + 1 < ATOMIC_WRITE_ATTEMPTS) {
          try {
            transaction.commit();
            // Success!
            break;
          } catch (Exception e) { // SUPPRESS CHECKSTYLE IllegalCatch
            // Transaction failed! Bad connection? Existence check rendered invalid?
            // Swallow exception and try again
            LOGGER.error(String.format("Failed to complete transaction attempt %d/%d: %s",
                i + 1, ATOMIC_WRITE_ATTEMPTS, transaction), e);
          }
        } else {
          // Last try: Any exception should be forwarded upstream
          transaction.commit();
        }
      }
    } catch (Exception e) { // SUPPRESS CHECKSTYLE IllegalCatch
      throw new PersisterException(Reason.STORAGE_ERROR, e);
    }
  }

  /**
   * Maps the provided external path into a framework-namespaced path. This translation MUST be
   * performed against all externally-provided paths, or else unintended data loss in ZK may
   * result!!
   *
   * <p>Examples:
   * <ul><li>"/foo" => "/dcos-service-svcname/foo"</li>
   * <li>"/" => "/dcos-service-svcname"</li>
   * <li>"" => "/dcos-service-svcname"</li></ul>
   */
  private String withFrameworkPrefix(String prefix) {
    String path = PersisterUtils.joinPaths(serviceRootPath, prefix);
    // Avoid any trailing slashes, which lead to STORAGE_ERRORs:
    while (path.endsWith(PersisterUtils.PATH_DELIM_STR)) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  private interface TransactionFactory {
    CuratorTransactionFinal build(CuratorFramework client, String serviceRootPath) throws Exception;
  }

  /**
   * Builder for constructing {@link CuratorPersister} instances.
   */
  public static final class Builder {
    private final String serviceName;

    private final String zookeeperHostPort;

    private RetryPolicy retryPolicy;

    private String username;

    private String password;

    private boolean lockEnabled;

    /**
     * Creates a new {@link Builder} instance which has been initialized with reasonable
     * default values.
     */
    private Builder(String serviceName, String zookeeperHostPort) {
      this.serviceName = serviceName;
      this.zookeeperHostPort = zookeeperHostPort;
      // Set defaults for customizable options:
      this.retryPolicy = CuratorUtils.getDefaultRetry();
      this.username = "";
      this.password = "";
      this.lockEnabled = true;
    }

    /**
     * Assigns a custom retry policy for the ZK server.
     *
     * @param retryPolicy The custom {@link RetryPolicy}
     */
    public Builder setRetryPolicy(RetryPolicy retryPolicy) {
      this.retryPolicy = retryPolicy;
      return this;
    }

    /**
     * Assigns credentials to be used when contacting ZK.
     *
     * @param zkUsername The ZK username
     * @param zkPassword The ZK password
     */
    public Builder setCredentials(String zkUsername, String zkPassword) {
      this.username = zkUsername;
      this.password = zkPassword;
      return this;
    }

    /**
     * Disables getting a curator lock before returning a {@link CuratorPersister}.
     * <p>
     * This should only be invoked in tests.
     */
    @VisibleForTesting
    public Builder disableLock() {
      this.lockEnabled = false;
      return this;
    }

    /**
     * Returns a new {@link CuratorPersister} instance using the provided settings,
     * using reasonable defaults where custom values were not specified.
     */
    public CuratorPersister build() {
      CuratorFrameworkFactory.Builder builder = CuratorFrameworkFactory.builder()
          .connectString(zookeeperHostPort)
          .retryPolicy(retryPolicy);
      if (!username.isEmpty() && !password.isEmpty()) {
        List<ACL> acls = new ArrayList<ACL>();
        acls.addAll(ZooDefs.Ids.CREATOR_ALL_ACL);
        acls.addAll(ZooDefs.Ids.READ_ACL_UNSAFE);

        String authenticationString = username + ":" + password;
        builder.authorization("digest", authenticationString.getBytes(StandardCharsets.UTF_8))
            .aclProvider(new ACLProvider() {
              @Override
              public List<ACL> getDefaultAcl() {
                return acls;
              }

              @Override
              public List<ACL> getAclForPath(String path) {
                return acls;
              }
            });
      } else if (!username.isEmpty() || !password.isEmpty()) {
        throw new IllegalArgumentException(
            "username and password must both be provided, or both must be empty.");
      }

      if (lockEnabled) {
        // Lock curator (using a separate client created from this builder) BEFORE returning access
        // to persister
        CuratorLocker.lock(serviceName, builder);
      }

      CuratorPersister persister = new CuratorPersister(serviceName, builder.build());
      CuratorUtils.initServiceName(persister, serviceName);
      return persister;
    }
  }

  /**
   * Creates and returns a transaction that will create and/or update data in the curator tree,
   * based on the current tree state.
   */
  private static final class SetTransactionFactory implements TransactionFactory {
    private final Map<String, byte[]> pathBytesMap;

    private SetTransactionFactory(Map<String, byte[]> pathBytesMap) {
      this.pathBytesMap = pathBytesMap;
    }

    public CuratorTransactionFinal build(
        CuratorFramework client,
        String serviceRootPath
    ) throws Exception
    {
      // List of paths that are known to exist, or which are about to be created by the transaction
      // Includes "known to exist" in order to avoid repeated lookups for the same path
      Set<String> existingAndPendingCreatePaths = new HashSet<>();

      CuratorTransactionFinal transaction = client
          .inTransaction()
          .check()
          .forPath(serviceRootPath)
          .and();
      for (Map.Entry<String, byte[]> entry : pathBytesMap.entrySet()) {
        String path = entry.getKey();
        if (!existingAndPendingCreatePaths.contains(path)
            && client.checkExists().forPath(path) == null)
        {
          // Path does not exist and is not being created: Create value (and any parents as needed).
          transaction = createParentsOf(client, path, transaction, existingAndPendingCreatePaths)
              .create().forPath(path, entry.getValue()).and();
          existingAndPendingCreatePaths.add(path);
        } else {
          // Path exists (or will exist): Update existing value.
          transaction = transaction.setData().forPath(path, entry.getValue()).and();
        }
      }
      return transaction;
    }
  }

  /**
   * Creates and returns a transaction that will remove data from the curator tree, based on the
   * current tree state.
   */
  private static final class ClearTransactionFactory implements TransactionFactory {
    private final Collection<String> pathsToClear;

    private ClearTransactionFactory(Collection<String> pathsToClear) {
      this.pathsToClear = pathsToClear;
    }

    public CuratorTransactionFinal build(
        CuratorFramework client,
        String serviceRootPath
    ) throws Exception
    {
      // List of paths which are about to be deleted by the transaction
      Set<String> pendingDeletePaths = new HashSet<>();

      CuratorTransactionFinal transaction = client
          .inTransaction()
          .check()
          .forPath(serviceRootPath)
          .and();
      for (String path : pathsToClear) {
        // if present, delete path and any children (unless already being deleted)
        if (!pendingDeletePaths.contains(path)
            && client.checkExists().forPath(path) != null)
        {
          transaction = deleteChildrenOf(client, path, transaction, pendingDeletePaths)
              .delete().forPath(path).and();
          pendingDeletePaths.add(path);
        }
      }
      return transaction;
    }
  }
}
