/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.broker.loadbalance;

import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.bookkeeper.test.PortManager;
import org.apache.bookkeeper.util.ZkUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.pulsar.broker.PulsarService;
import org.apache.pulsar.broker.ServiceConfiguration;
import org.apache.pulsar.broker.admin.AdminResource;
import org.apache.pulsar.broker.cache.ResourceQuotaCache;
import org.apache.pulsar.broker.loadbalance.BrokerHostUsage;
import org.apache.pulsar.broker.loadbalance.LoadManager;
import org.apache.pulsar.broker.loadbalance.LoadRanker;
import org.apache.pulsar.broker.loadbalance.LoadReport;
import org.apache.pulsar.broker.loadbalance.LoadResourceQuotaUpdaterTask;
import org.apache.pulsar.broker.loadbalance.LoadSheddingTask;
import org.apache.pulsar.broker.loadbalance.ResourceUnit;
import org.apache.pulsar.broker.loadbalance.impl.GenericBrokerHostUsageImpl;
import org.apache.pulsar.broker.loadbalance.impl.LinuxBrokerHostUsageImpl;
import org.apache.pulsar.broker.loadbalance.impl.PulsarLoadReportImpl;
import org.apache.pulsar.broker.loadbalance.impl.PulsarResourceDescription;
import org.apache.pulsar.broker.loadbalance.impl.ResourceAvailabilityRanker;
import org.apache.pulsar.broker.loadbalance.impl.SimpleLoadCalculatorImpl;
import org.apache.pulsar.broker.loadbalance.impl.SimpleLoadManagerImpl;
import org.apache.pulsar.broker.loadbalance.impl.SimpleResourceUnit;
import org.apache.pulsar.client.admin.BrokerStats;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.common.naming.NamespaceBundle;
import org.apache.pulsar.common.naming.NamespaceName;
import org.apache.pulsar.common.policies.data.AutoFailoverPolicyData;
import org.apache.pulsar.common.policies.data.AutoFailoverPolicyType;
import org.apache.pulsar.common.policies.data.NamespaceIsolationData;
import org.apache.pulsar.common.policies.data.ResourceQuota;
import org.apache.pulsar.common.policies.impl.NamespaceIsolationPolicies;
import org.apache.pulsar.common.util.ObjectMapperFactory;
import org.apache.pulsar.policies.data.loadbalancer.BrokerUsage;
import org.apache.pulsar.policies.data.loadbalancer.JvmUsage;
import org.apache.pulsar.policies.data.loadbalancer.NamespaceBundleStats;
import org.apache.pulsar.policies.data.loadbalancer.ResourceUnitRanking;
import org.apache.pulsar.policies.data.loadbalancer.ResourceUsage;
import org.apache.pulsar.policies.data.loadbalancer.SystemResourceUsage;
import org.apache.pulsar.zookeeper.LocalBookkeeperEnsemble;
import org.apache.pulsar.zookeeper.LocalZooKeeperCache;
import org.apache.pulsar.zookeeper.ZooKeeperChildrenCache;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Sets;

import jersey.repackaged.com.google.common.collect.Maps;

/**
 */
public class SimpleLoadManagerImplTest {
    LocalBookkeeperEnsemble bkEnsemble;

    URL url1;
    PulsarService pulsar1;
    PulsarAdmin admin1;

    URL url2;
    PulsarService pulsar2;
    PulsarAdmin admin2;

    BrokerStats brokerStatsClient1;
    BrokerStats brokerStatsClient2;

    String primaryHost;
    String secondaryHost;

    ExecutorService executor = new ThreadPoolExecutor(5, 20, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

    private final int ZOOKEEPER_PORT = PortManager.nextFreePort();
    private final int PRIMARY_BROKER_WEBSERVICE_PORT = PortManager.nextFreePort();
    private final int SECONDARY_BROKER_WEBSERVICE_PORT = PortManager.nextFreePort();
    private final int PRIMARY_BROKER_PORT = PortManager.nextFreePort();
    private final int SECONDARY_BROKER_PORT = PortManager.nextFreePort();
    private static final Logger log = LoggerFactory.getLogger(SimpleLoadManagerImplTest.class);

    static {
        System.setProperty("test.basePort", "16100");
    }

    @BeforeMethod
    void setup() throws Exception {

        // Start local bookkeeper ensemble
        bkEnsemble = new LocalBookkeeperEnsemble(3, ZOOKEEPER_PORT, PortManager.nextFreePort());
        bkEnsemble.start();

        // Start broker 1
        ServiceConfiguration config1 = spy(new ServiceConfiguration());
        config1.setClusterName("use");
        config1.setWebServicePort(PRIMARY_BROKER_WEBSERVICE_PORT);
        config1.setZookeeperServers("127.0.0.1" + ":" + ZOOKEEPER_PORT);
        config1.setBrokerServicePort(PRIMARY_BROKER_PORT);
        pulsar1 = new PulsarService(config1);

        pulsar1.start();

        url1 = new URL("http://127.0.0.1" + ":" + PRIMARY_BROKER_WEBSERVICE_PORT);
        admin1 = new PulsarAdmin(url1, (Authentication) null);
        brokerStatsClient1 = admin1.brokerStats();
        primaryHost = String.format("http://%s:%d", InetAddress.getLocalHost().getHostName(),
                PRIMARY_BROKER_WEBSERVICE_PORT);

        // Start broker 2
        ServiceConfiguration config2 = new ServiceConfiguration();
        config2.setClusterName("use");
        config2.setWebServicePort(SECONDARY_BROKER_WEBSERVICE_PORT);
        config2.setZookeeperServers("127.0.0.1" + ":" + ZOOKEEPER_PORT);
        config2.setBrokerServicePort(SECONDARY_BROKER_PORT);
        pulsar2 = new PulsarService(config2);

        pulsar2.start();

        url2 = new URL("http://127.0.0.1" + ":" + SECONDARY_BROKER_WEBSERVICE_PORT);
        admin2 = new PulsarAdmin(url2, (Authentication) null);
        brokerStatsClient2 = admin2.brokerStats();
        secondaryHost = String.format("http://%s:%d", InetAddress.getLocalHost().getHostName(),
                SECONDARY_BROKER_WEBSERVICE_PORT);
        Thread.sleep(100);
    }

    @AfterMethod
    void shutdown() throws Exception {
        log.info("--- Shutting down ---");
        executor.shutdown();

        admin1.close();
        admin2.close();

        pulsar2.close();
        pulsar1.close();

        bkEnsemble.stop();
    }

    private void createNamespacePolicies(PulsarService pulsar) throws Exception {
        NamespaceIsolationPolicies policies = new NamespaceIsolationPolicies();
        // set up policy that use this broker as primary
        NamespaceIsolationData policyData = new NamespaceIsolationData();
        policyData.namespaces = new ArrayList<String>();
        policyData.namespaces.add("pulsar/use/primary-ns.*");
        policyData.primary = new ArrayList<String>();
        policyData.primary.add(pulsar1.getAdvertisedAddress() + "*");
        policyData.secondary = new ArrayList<String>();
        policyData.secondary.add("prod2-broker([78]).messaging.usw.example.co.*");
        policyData.auto_failover_policy = new AutoFailoverPolicyData();
        policyData.auto_failover_policy.policy_type = AutoFailoverPolicyType.min_available;
        policyData.auto_failover_policy.parameters = new HashMap<String, String>();
        policyData.auto_failover_policy.parameters.put("min_limit", "1");
        policyData.auto_failover_policy.parameters.put("usage_threshold", "100");
        policies.setPolicy("primaryBrokerPolicy", policyData);

        ObjectMapper jsonMapper = ObjectMapperFactory.create();
        ZooKeeper globalZk = pulsar.getGlobalZkCache().getZooKeeper();
        ZkUtils.createFullPathOptimistic(globalZk, AdminResource.path("clusters", "use", "namespaceIsolationPolicies"),
                new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        byte[] content = jsonMapper.writeValueAsBytes(policies.getPolicies());
        globalZk.setData(AdminResource.path("clusters", "use", "namespaceIsolationPolicies"), content, -1);

    }

    @Test(enabled = true)
    public void testBasicBrokerSelection() throws Exception {
        LoadManager loadManager = new SimpleLoadManagerImpl(pulsar1);
        PulsarResourceDescription rd = new PulsarResourceDescription();
        rd.put("memory", new ResourceUsage(1024, 4096));
        rd.put("cpu", new ResourceUsage(10, 100));
        rd.put("bandwidthIn", new ResourceUsage(250 * 1024, 1024 * 1024));
        rd.put("bandwidthOut", new ResourceUsage(550 * 1024, 1024 * 1024));

        ResourceUnit ru1 = new SimpleResourceUnit("http://prod2-broker7.messaging.usw.example.com:8080", rd);
        Set<ResourceUnit> rus = new HashSet<ResourceUnit>();
        rus.add(ru1);
        LoadRanker lr = new ResourceAvailabilityRanker();
        AtomicReference<Map<Long, Set<ResourceUnit>>> sortedRankingsInstance = new AtomicReference<>(Maps.newTreeMap());
        sortedRankingsInstance.get().put(lr.getRank(rd), rus);

        Field sortedRankings = SimpleLoadManagerImpl.class.getDeclaredField("sortedRankings");
        sortedRankings.setAccessible(true);
        sortedRankings.set(loadManager, sortedRankingsInstance);

        ResourceUnit found = ((SimpleLoadManagerImpl) loadManager)
                .getLeastLoaded(new NamespaceName("pulsar/use/primary-ns.10"));
        // broker is not active so found should be null
        assertEquals(found, null, "found a broker when expected none to be found");

    }

    private void setObjectField(Class objClass, Object objInstance, String fieldName, Object newValue)
            throws Exception {
        Field field = objClass.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(objInstance, newValue);
    }

    @Test(enabled = true)
    public void testPrimary() throws Exception {
        createNamespacePolicies(pulsar1);
        LoadManager loadManager = new SimpleLoadManagerImpl(pulsar1);
        PulsarResourceDescription rd = new PulsarResourceDescription();
        rd.put("memory", new ResourceUsage(1024, 4096));
        rd.put("cpu", new ResourceUsage(10, 100));
        rd.put("bandwidthIn", new ResourceUsage(250 * 1024, 1024 * 1024));
        rd.put("bandwidthOut", new ResourceUsage(550 * 1024, 1024 * 1024));

        ResourceUnit ru1 = new SimpleResourceUnit(
                "http://" + pulsar1.getAdvertisedAddress() + ":" + pulsar1.getConfiguration().getWebServicePort(), rd);
        Set<ResourceUnit> rus = new HashSet<ResourceUnit>();
        rus.add(ru1);
        LoadRanker lr = new ResourceAvailabilityRanker();

        // inject the load report and rankings
        Map<ResourceUnit, org.apache.pulsar.policies.data.loadbalancer.LoadReport> loadReports = new HashMap<>();
        org.apache.pulsar.policies.data.loadbalancer.LoadReport loadReport = new org.apache.pulsar.policies.data.loadbalancer.LoadReport();
        loadReport.setSystemResourceUsage(new SystemResourceUsage());
        loadReports.put(ru1, loadReport);
        setObjectField(SimpleLoadManagerImpl.class, loadManager, "currentLoadReports", loadReports);

        ResourceUnitRanking ranking = new ResourceUnitRanking(loadReport.getSystemResourceUsage(),
                new HashSet<String>(), new ResourceQuota(), new HashSet<String>(), new ResourceQuota());
        Map<ResourceUnit, ResourceUnitRanking> rankings = new HashMap<>();
        rankings.put(ru1, ranking);
        setObjectField(SimpleLoadManagerImpl.class, loadManager, "resourceUnitRankings", rankings);

        AtomicReference<Map<Long, Set<ResourceUnit>>> sortedRankingsInstance = new AtomicReference<>(Maps.newTreeMap());
        sortedRankingsInstance.get().put(lr.getRank(rd), rus);
        setObjectField(SimpleLoadManagerImpl.class, loadManager, "sortedRankings", sortedRankingsInstance);

        ResourceUnit found = ((SimpleLoadManagerImpl) loadManager)
                .getLeastLoaded(new NamespaceName("pulsar/use/primary-ns.10"));
        // broker is not active so found should be null
        assertNotEquals(found, null, "did not find a broker when expected one to be found");

    }

    @Test(enabled = false)
    public void testPrimarySecondary() throws Exception {
        createNamespacePolicies(pulsar1);
        LocalZooKeeperCache mockCache = mock(LocalZooKeeperCache.class);
        ZooKeeperChildrenCache zooKeeperChildrenCache = mock(ZooKeeperChildrenCache.class);

        Set<String> activeBrokers = Sets.newHashSet("prod2-broker7.messaging.use.example.com:8080",
                "prod2-broker8.messaging.use.example.com:8080", "prod2-broker9.messaging.use.example.com:8080");
        when(mockCache.getChildren(SimpleLoadManagerImpl.LOADBALANCE_BROKERS_ROOT)).thenReturn(activeBrokers);
        when(zooKeeperChildrenCache.get()).thenReturn(activeBrokers);
        when(zooKeeperChildrenCache.get(SimpleLoadManagerImpl.LOADBALANCE_BROKERS_ROOT)).thenReturn(activeBrokers);

        Field zkCacheField = PulsarService.class.getDeclaredField("localZkCache");
        zkCacheField.setAccessible(true);

        LocalZooKeeperCache originalLZK1 = (LocalZooKeeperCache) zkCacheField.get(pulsar1);
        LocalZooKeeperCache originalLZK2 = (LocalZooKeeperCache) zkCacheField.get(pulsar2);
        log.info("lzk are {} 2: {}", originalLZK1.getChildren(SimpleLoadManagerImpl.LOADBALANCE_BROKERS_ROOT),
                originalLZK2.getChildren(SimpleLoadManagerImpl.LOADBALANCE_BROKERS_ROOT));
        zkCacheField.set(pulsar1, mockCache);

        LocalZooKeeperCache newZk = (LocalZooKeeperCache) pulsar1.getLocalZkCache();
        log.info("lzk mocked are {}", newZk.getChildren(SimpleLoadManagerImpl.LOADBALANCE_BROKERS_ROOT));

        ZooKeeperChildrenCache availableActiveBrokers = new ZooKeeperChildrenCache(pulsar1.getLocalZkCache(),
                SimpleLoadManagerImpl.LOADBALANCE_BROKERS_ROOT);

        log.info("lzk mocked active brokers are {}",
                availableActiveBrokers.get(SimpleLoadManagerImpl.LOADBALANCE_BROKERS_ROOT));

        LoadManager loadManager = new SimpleLoadManagerImpl(pulsar1);

        PulsarResourceDescription rd = new PulsarResourceDescription();
        rd.put("memory", new ResourceUsage(1024, 4096));
        rd.put("cpu", new ResourceUsage(10, 100));
        rd.put("bandwidthIn", new ResourceUsage(250 * 1024, 1024 * 1024));
        rd.put("bandwidthOut", new ResourceUsage(550 * 1024, 1024 * 1024));

        ResourceUnit ru1 = new SimpleResourceUnit("http://prod2-broker7.messaging.usw.example.com:8080", rd);
        Set<ResourceUnit> rus = new HashSet<ResourceUnit>();
        rus.add(ru1);
        LoadRanker lr = new ResourceAvailabilityRanker();
        AtomicReference<Map<Long, Set<ResourceUnit>>> sortedRankingsInstance = new AtomicReference<>(Maps.newTreeMap());
        sortedRankingsInstance.get().put(lr.getRank(rd), rus);

        Field sortedRankings = SimpleLoadManagerImpl.class.getDeclaredField("sortedRankings");
        sortedRankings.setAccessible(true);
        sortedRankings.set(loadManager, sortedRankingsInstance);

        ResourceUnit found = ((SimpleLoadManagerImpl) loadManager)
                .getLeastLoaded(new NamespaceName("pulsar/use/primary-ns.10"));
        assertEquals(found.getResourceId(), ru1.getResourceId());

        zkCacheField.set(pulsar1, originalLZK1);
    }

    @Test
    public void testResourceDescription() {

        PulsarResourceDescription rd = new PulsarResourceDescription();
        rd.put("memory", new ResourceUsage(1024, 4096));
        rd.put("cpu", new ResourceUsage(10, 100));
        rd.put("bandwidthIn", new ResourceUsage(250 * 1024, 1024 * 1024));
        rd.put("bandwidthOut", new ResourceUsage(550 * 1024, 1024 * 1024));

        PulsarResourceDescription rd1 = new PulsarResourceDescription();
        rd1.put("memory", new ResourceUsage(2048, 4096));
        rd1.put("cpu", new ResourceUsage(50, 100));
        rd1.put("bandwidthIn", new ResourceUsage(550 * 1024, 1024 * 1024));
        rd1.put("bandwidthOut", new ResourceUsage(850 * 1024, 1024 * 1024));

        assertTrue(rd.compareTo(rd1) == 1);
        assertTrue(rd1.calculateRank() > rd.calculateRank());

        SimpleLoadCalculatorImpl calc = new SimpleLoadCalculatorImpl();
        calc.recaliberateResourceUsagePerServiceUnit(null);
        assertNull(calc.getResourceDescription(null));

    }

    @Test
    public void testLoadReportParsing() throws Exception {

        ObjectMapper mapper = ObjectMapperFactory.create();
        org.apache.pulsar.policies.data.loadbalancer.LoadReport reportData = new org.apache.pulsar.policies.data.loadbalancer.LoadReport();
        reportData.setName("b1");
        SystemResourceUsage resource = new SystemResourceUsage();
        ResourceUsage resourceUsage = new ResourceUsage();
        resource.setBandwidthIn(resourceUsage);
        resource.setBandwidthOut(resourceUsage);
        resource.setMemory(resourceUsage);
        resource.setCpu(resourceUsage);
        reportData.setSystemResourceUsage(resource);
        String loadReportJson = mapper.writeValueAsString(reportData);
        LoadReport loadReport = PulsarLoadReportImpl.parse(loadReportJson);
        assertEquals(
                loadReport.getResourceUnitDescription().getResourceUsage().get("bandwidthIn").compareTo(resourceUsage),
                0);
    }

    @Test(enabled = true)
    public void testDoLoadShedding() throws Exception {
        LoadManager loadManager = spy(new SimpleLoadManagerImpl(pulsar1));
        PulsarResourceDescription rd = new PulsarResourceDescription();
        rd.put("memory", new ResourceUsage(1024, 4096));
        rd.put("cpu", new ResourceUsage(10, 100));
        rd.put("bandwidthIn", new ResourceUsage(250 * 1024, 1024 * 1024));
        rd.put("bandwidthOut", new ResourceUsage(550 * 1024, 1024 * 1024));

        ResourceUnit ru1 = new SimpleResourceUnit("http://pulsar-broker1.com:8080", rd);
        ResourceUnit ru2 = new SimpleResourceUnit("http://pulsar-broker2.com:8080", rd);
        Set<ResourceUnit> rus = new HashSet<ResourceUnit>();
        rus.add(ru1);
        rus.add(ru2);
        LoadRanker lr = new ResourceAvailabilityRanker();
        AtomicReference<Map<Long, Set<ResourceUnit>>> sortedRankingsInstance = new AtomicReference<>(Maps.newTreeMap());
        sortedRankingsInstance.get().put(lr.getRank(rd), rus);

        Field sortedRankings = SimpleLoadManagerImpl.class.getDeclaredField("sortedRankings");
        sortedRankings.setAccessible(true);
        sortedRankings.set(loadManager, sortedRankingsInstance);

        // inject the load report and rankings
        SystemResourceUsage systemResource = new SystemResourceUsage();
        systemResource.setBandwidthIn(new ResourceUsage(90, 100));
        Map<String, NamespaceBundleStats> stats = Maps.newHashMap();
        NamespaceBundleStats nsb1 = new NamespaceBundleStats();
        nsb1.msgRateOut = 10000;
        NamespaceBundleStats nsb2 = new NamespaceBundleStats();
        nsb2.msgRateOut = 10000;
        stats.put("property/cluster/namespace1/0x00000000_0xFFFFFFFF", nsb1);
        stats.put("property/cluster/namespace2/0x00000000_0xFFFFFFFF", nsb2);

        Map<ResourceUnit, org.apache.pulsar.policies.data.loadbalancer.LoadReport> loadReports = new HashMap<>();
        org.apache.pulsar.policies.data.loadbalancer.LoadReport loadReport1 = new org.apache.pulsar.policies.data.loadbalancer.LoadReport();
        loadReport1.setSystemResourceUsage(systemResource);
        loadReport1.setBundleStats(stats);
        org.apache.pulsar.policies.data.loadbalancer.LoadReport loadReport2 = new org.apache.pulsar.policies.data.loadbalancer.LoadReport();
        loadReport2.setSystemResourceUsage(new SystemResourceUsage());
        loadReport2.setBundleStats(stats);
        loadReports.put(ru1, loadReport1);
        loadReports.put(ru2, loadReport2);
        setObjectField(SimpleLoadManagerImpl.class, loadManager, "currentLoadReports", loadReports);

        ((SimpleLoadManagerImpl) loadManager).doLoadShedding();
        verify(loadManager, atLeastOnce()).doLoadShedding();
    }

    // Test that bundles belonging to the same namespace are evenly distributed.
    @Test
    public void testEvenBundleDistribution() throws Exception {
        final NamespaceBundle[] bundles = LoadBalancerTestingUtils
                .makeBundles(pulsar1.getNamespaceService().getNamespaceBundleFactory(), "pulsar", "use", "test", 16);
        final ResourceQuota quota = new ResourceQuota();
        final String quotaZPath = String.format("%s/%s/%s", ResourceQuotaCache.RESOURCE_QUOTA_ROOT, "namespace",
                bundles[0]);
        // Create high message rate quota for the first bundle to make it unlikely to be a coincidence of even
        // distribution.
        ZkUtils.createFullPathOptimistic(pulsar1.getZkClient(), quotaZPath,
                ObjectMapperFactory.getThreadLocal().writeValueAsBytes(quota), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        int numAssignedToPrimary = 0;
        int numAssignedToSecondary = 0;
        pulsar1.getConfiguration().setLoadBalancerPlacementStrategy(SimpleLoadManagerImpl.LOADBALANCER_STRATEGY_LLS);
        final SimpleLoadManagerImpl loadManager = (SimpleLoadManagerImpl) pulsar1.getLoadManager().get();

        for (final NamespaceBundle bundle : bundles) {
            if (loadManager.getLeastLoaded(bundle).getResourceId().equals(primaryHost)) {
                ++numAssignedToPrimary;
            } else {
                ++numAssignedToSecondary;
            }
            // Check that number of assigned bundles are equivalent when an even number have been assigned.
            if ((numAssignedToPrimary + numAssignedToSecondary) % 2 == 0) {
                assert (numAssignedToPrimary == numAssignedToSecondary);
            }
        }
    }

    @Test
    public void testNamespaceBundleStats() {
        NamespaceBundleStats nsb1 = new NamespaceBundleStats();
        nsb1.msgRateOut = 10000;
        nsb1.producerCount = 1;
        nsb1.consumerCount = 1;
        nsb1.cacheSize = 4;
        nsb1.msgRateIn = 500;
        nsb1.msgThroughputIn = 30;
        nsb1.msgThroughputOut = 30;
        NamespaceBundleStats nsb2 = new NamespaceBundleStats();
        nsb2.msgRateOut = 20000;
        nsb2.producerCount = 300;
        nsb2.consumerCount = 300;
        nsb2.cacheSize = 110000;
        nsb2.msgRateIn = 5000;
        nsb2.msgThroughputIn = 110000.0;
        nsb2.msgThroughputOut = 110000.0;
        assertTrue(nsb1.compareTo(nsb2) == -1);
        assertTrue(nsb1.compareByMsgRate(nsb2) == -1);
        assertTrue(nsb1.compareByTopicConnections(nsb2) == -1);
        assertTrue(nsb1.compareByCacheSize(nsb2) == -1);
        assertTrue(nsb1.compareByBandwidthOut(nsb2) == -1);
        assertTrue(nsb1.compareByBandwidthIn(nsb2) == -1);
    }

    @Test
    public void testBrokerHostUsage() {
        BrokerHostUsage brokerUsage;
        if (SystemUtils.IS_OS_LINUX) {
            brokerUsage = new LinuxBrokerHostUsageImpl(pulsar1);
        } else {
            brokerUsage = new GenericBrokerHostUsageImpl(pulsar1);
        }
        brokerUsage.getBrokerHostUsage();
        // Ok
    }

    @Test
    public void testTask() throws Exception {
        LoadManager loadManager = mock(LoadManager.class);
        AtomicReference<LoadManager> atomicLoadManager = new AtomicReference<>(loadManager);
        LoadResourceQuotaUpdaterTask task1 = new LoadResourceQuotaUpdaterTask(atomicLoadManager);
        task1.run();
        verify(loadManager, times(1)).writeResourceQuotasToZooKeeper();

        LoadSheddingTask task2 = new LoadSheddingTask(atomicLoadManager);
        task2.run();
        verify(loadManager, times(1)).doLoadShedding();
    }

    @Test
    public void testUsage() {
        Map<String, Object> metrics = Maps.newHashMap();
        metrics.put("brk_conn_cnt", new Long(1));
        metrics.put("brk_repl_conn_cnt", new Long(1));
        metrics.put("jvm_thread_cnt", new Long(1));
        BrokerUsage brokerUsage = BrokerUsage.populateFrom(metrics);
        assertEquals(brokerUsage.getConnectionCount(), 1);
        assertEquals(brokerUsage.getReplicationConnectionCount(), 1);
        JvmUsage jvmUage = JvmUsage.populateFrom(metrics);
        assertEquals(jvmUage.getThreadCount(), 1);
        SystemResourceUsage usage = new SystemResourceUsage();
        double usageLimit = 10.0;
        usage.setBandwidthIn(new ResourceUsage(usageLimit, usageLimit));
        assertEquals(usage.getBandwidthIn().usage, usageLimit);
        usage.reset();
        assertNotEquals(usage.getBandwidthIn().usage, usageLimit);
    }

}
