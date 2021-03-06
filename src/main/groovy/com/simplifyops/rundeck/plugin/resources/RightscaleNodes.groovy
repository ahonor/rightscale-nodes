package com.simplifyops.rundeck.plugin.resources

import com.codahale.metrics.ConsoleReporter
import com.codahale.metrics.Gauge
import com.codahale.metrics.MetricRegistry
import com.dtolabs.rundeck.core.common.INodeSet
import com.dtolabs.rundeck.core.common.NodeEntryImpl
import com.dtolabs.rundeck.core.common.NodeSetImpl
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException
import com.dtolabs.rundeck.core.resources.ResourceModelSource
import com.dtolabs.rundeck.core.resources.ResourceModelSourceException
import org.apache.log4j.Logger

import java.util.concurrent.TimeUnit

public class RightscaleNodes implements ResourceModelSource {
    static Logger logger = Logger.getLogger(RightscaleNodes.class);

    /**
     * Configuration parameters.
     */
    private Properties configuration;

    /**
     * The nodeset filled by the query result.
     */
    private INodeSet nodeset;

    private RightscaleAPI query;

    private RightscaleCache cache;

    private CacheLoader loader;

    private boolean initialized = false

    private long lastRefresh = 0;

    private Thread refreshThread

    private long lastRefreshDuration = 0L

    private MetricRegistry metrics = RightscaleNodesFactory.metrics

    /**
     * Default constructor used by RightscaleNodesFactory. Uses RightscaleAPIRequest for querying.
     * @param configuration Properties containing plugin configuration values.
     */
    public RightscaleNodes(Properties configuration) {
        this(configuration, new RightscaleAPIRequest(configuration))
    }

    /**
     * Base constructor.
     * @param configuration Properties containing plugin configuration values.
     * @param query Rightscale API client used to query resources.
     */
    public RightscaleNodes(Properties configuration, RightscaleAPI query) {
        this(configuration, query, new RightscaleBasicCache())
    }

    /**
     * Base constructor.
     * @param configuration Properties containing plugin configuration values.
     * @param api Rightscale API client used to query resources.
     */
    public RightscaleNodes(Properties configuration, RightscaleAPI api, RightscaleCache cache) {

        this.configuration = configuration
        this.query = api
        this.cache = cache

        logger.info("DEBUG: New RightscaleNodes object created.")
    }

    /**
     * validate required configuration params are valid. Used by the factory.
     * @throws ConfigurationException
     */
    void validate() throws ConfigurationException {
        if (null == configuration.getProperty(RightscaleNodesFactory.EMAIL)) {
            throw new ConfigurationException("email is required");
        }
        if (null == configuration.getProperty(RightscaleNodesFactory.PASSWORD)) {
            throw new ConfigurationException("password is required");
        }
        if (null == configuration.getProperty(RightscaleNodesFactory.ACCOUNT)) {
            throw new ConfigurationException("account is required");
        }
        if (null == configuration.getProperty(RightscaleNodesFactory.REFRESH_INTERVAL)) {
            throw new ConfigurationException("interval is required");
        }
        final String interval = configuration.getProperty(RightscaleNodesFactory.REFRESH_INTERVAL)
        try {
            Integer.parseInt(interval);
        } catch (NumberFormatException e) {
            throw new ConfigurationException(RightscaleNodesFactory.REFRESH_INTERVAL + " value is not valid: " + interval);
        }
        if (null == configuration.getProperty(RightscaleNodesFactory.INPUT_PATT)) {
            throw new ConfigurationException("inputs is required");
        }
        final String timeout = configuration.getProperty(RightscaleNodesFactory.HTTP_TIMEOUT)
        if (null != timeout) {
            try {
                Integer.parseInt(timeout);
            } catch (NumberFormatException e) {
                throw new ConfigurationException(RightscaleNodesFactory.HTTP_TIMEOUT + " value is not valid: " + timeout);
            }
        }
        final String metricsInterval = configuration.getProperty(RightscaleNodesFactory.METRICS_INTVERVAL)
        if (null != metricsInterval) {
            try {
                Integer.parseInt(metricsInterval);
            } catch (NumberFormatException e) {
                throw new ConfigurationException(RightscaleNodesFactory.METRICS_INTVERVAL + " value is not valid: " + metricsInterval);
            }
        }
    }
    /**
     * Initialize the cache, query and metrics objects.
     */
    void initialize() {
        if (!initialized) {

            // Cache
            int refreshSecs = Integer.parseInt(configuration.getProperty(RightscaleNodesFactory.REFRESH_INTERVAL));
            this.cache.setRefreshInterval(refreshSecs * 1000)
            this.cache.initialize()

            // Query
            this.query.initialize()

            // Metrics
            if (!metrics.getGauges().containsKey(MetricRegistry.name(RightscaleNodes.class, "nodes.count"))) {
                metrics.register(MetricRegistry.name(RightscaleNodes.class, "nodes.count"), new Gauge<Integer>() {
                    @Override
                    public Integer getValue() {
                        return (null == nodeset) ? 0 : nodeset.getNodes().size()
                    }
                });
            }

            if (!metrics.getGauges().containsKey(MetricRegistry.name(RightscaleNodes.class, "refresh.last.ago"))) {
                metrics.register(MetricRegistry.name(RightscaleNodes.class, "refresh.last.ago"), new Gauge<Integer>() {
                    @Override
                    public Integer getValue() {
                        return (null == nodeset) ? 0 : sinceLastRefresh()
                    }
                });
            }

            if (!metrics.getGauges().containsKey(MetricRegistry.name(RightscaleNodes.class, "refresh.last.duration"))) {
                metrics.register(MetricRegistry.name(RightscaleNodes.class, "refresh.last.duration"), new Gauge<Integer>() {
                    @Override
                    public Integer getValue() {
                        return lastRefreshDuration
                    }
                });
            }

            if (!metrics.getGauges().containsKey(MetricRegistry.name(RightscaleNodes.class, "refresh.interval"))) {
                metrics.register(MetricRegistry.name(RightscaleNodes.class, "refresh.interval"), new Gauge<Integer>() {
                    @Override
                    public Integer getValue() {
                        return Integer.parseInt(configuration.getProperty(RightscaleNodesFactory.REFRESH_INTERVAL))
                    }
                });
            }

            if (configuration.containsKey(RightscaleNodesFactory.METRICS_INTVERVAL)) {
                final ConsoleReporter reporter = ConsoleReporter.forRegistry(metrics)
                        .convertRatesTo(TimeUnit.SECONDS)
                        .convertDurationsTo(TimeUnit.MILLISECONDS)
                        .build();
                reporter.start(Integer.parseInt(configuration.getProperty(RightscaleNodesFactory.METRICS_INTVERVAL)), TimeUnit.MINUTES);
            }

            // done.
            initialized = true
        }
    }

    /**
     * Populate the cache from query data.
     */
    void loadCache() {
        // Create the configured kind of cache loading strategy.
        if (null == loader) {
            if (Boolean.parseBoolean(configuration.getProperty(RightscaleNodesFactory.ALL_RESOURCES))) {
                loader = CacheLoader.create(CacheLoader.STRATEGY_FULL)
            } else {
                loader = CacheLoader.create(CacheLoader.STRATEGY_MINIMUM)
            }
        }

        loader.load(cache, query)
    }

    /**
     * Query RightScale for their instances and return them as Nodes.
     */
    @Override
    public synchronized INodeSet getNodes() throws ResourceModelSourceException {

        // Initialize query and cache instances in case this is the first time through.
        initialize()

        /**
         * Haven't got any nodes yet so get them synchronously.
         */
        if (null == nodeset) {
            System.println("DEBUG: Empty nodeset. Synchronously loading nodes.")
            logger.info("Empty nodeset. Synchronously loading nodes.")
            nodeset = refresh()

        } else {
            def ago = sinceLastRefresh()

            if (!needsRefresh()) {
                System.println("DEBUG: Returning nodes from last refresh. (updated: ${ago} secs ago)")
                logger.info("Returning nodes from last refresh. (updated: ${ago} secs ago)")

                return nodeset;
            }


            logger.info "Nodes need a refresh. Last refresh ${ago} secs ago."
            System.out.println("DEBUG: Nodes need a refresh. Last refresh ${ago} secs ago.")

            /**
             * Query asynchronously.
             */

            if (!asyncRefreshRunning()) {
                refreshThread = Thread.start { nodeset = refresh() }
                logger.info("Running refresh in background thread: ${refreshThread.id}")
                System.out.println("DEBUG: Running refresh in background thread: ${refreshThread.id}")
            } else {
                logger.info("Refresh thread already running. (thread-id: " + refreshThread.id + ")")
                System.out.println("DEBUG: Refresh thread already running. (thread-id: " + refreshThread.id + ")")
                metrics.counter(MetricRegistry.name(RightscaleNodes.class, "refresh.request.skipped")).inc();

                return nodeset
            }
        }

        /**
         * Return the nodeset
         */
        return nodeset;
    }

    /**
     * Returns true if the last refresh time was longer ago than the refresh interval.
     */
    boolean needsRefresh() {
        def refreshInterval =  configuration.getProperty(RightscaleNodesFactory.REFRESH_INTERVAL).toInteger()
        def now = System.currentTimeMillis()
        def delta = ((now - lastRefresh)/1000)
        def next = (refreshInterval - delta)
        System.out.println("DEBUG: needsRefresh(): refresh interval: ${refreshInterval} secs, next refresh in ${next} secs")
        return refreshInterval <= 0 || (delta > refreshInterval);
    }

    private boolean asyncRefreshRunning() {
        return null != refreshThread && refreshThread.isAlive()
    }

    private long sinceLastRefresh() {
        return TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - lastRefresh)
    }

    private final def refreshCount = metrics.counter(MetricRegistry.name(RightscaleNodes.class, "refresh.request.total"));
    def refreshDuration = metrics.timer(MetricRegistry.name(RightscaleNodes.class, 'refresh.duration'))

    /**
     * Query the RightScale API for instances and map them to Nodes.
     *
     * @return nodeset of Nodes
     */
    INodeSet refresh() {
        def long starttime = System.currentTimeMillis()
        def refreshDuration = refreshDuration.time()
        System.out.println("DEBUG: Refresh started.")
        logger.info("Refresh started.")

        // load up the cache.
        loadCache()

        /**
         * Generate the nodes.
         */
        INodeSet nodes = new NodeSetImpl();
        // Generate Nodes from Instances launched by Servers.
        nodes.putNodes(populateServerNodes(cache))
        // Generate Nodes from Instances launched by ServerArrays.
        nodes.putNodes(populateServerArrayNodes(cache))

        // update the refresh timestamp
        lastRefresh = System.currentTimeMillis();
        System.out.println("DEBUG: last-refresh: ${new Date(lastRefresh).toString()}")

        lastRefreshDuration = (lastRefresh - starttime)
        System.println("DEBUG: Refresh ended. (nodes: ${nodes.getNodes().size()}, duration: ${lastRefreshDuration})")
        logger.info("Refresh ended. (nodes: ${nodes.getNodes().size()}, duration: ${lastRefreshDuration})")

        refreshDuration.stop()
        refreshCount.inc()

        return nodes;
    }

    /**
     * Generate Nodes from Instances launched by Servers.
     * @param api the RightscaleQuery
     * @return a node set of Nodes
     */
    INodeSet populateServerNodes(RightscaleAPI api) {
        def long starttime = System.currentTimeMillis()
        def timer = metrics.timer(MetricRegistry.name(RightscaleNodes, 'populateServerNodes.duration')).time()

        /**
         * Create a node set from the result.
         */
        def nodeset = new NodeSetImpl();
        /**
         * List the Servers
         */
        def servers = api.getServers().values()

        // Only process operational servers with a current instance.
        def operationalServers = servers.findAll { "operational".equals(it.attributes['state']) && it.links.containsKey('current_instance') }
        logger.info("Populating nodes for servers. (count: ${servers.size()}")
        System.out.println("DEBUG: Populating nodes for servers. (count: ${servers.size()}")

        operationalServers.each { server ->
            logger.info("Retreiving current_instance for server: ${server.attributes['name']}")
            /**
             * Get the cloud so we lookup the instance.
             */
            //def cloud_href = server.links['cloud']
            def cloud_href = server.cloud
            if (null == cloud_href) {
                logger.error("Could not determine the cloud for this server: ${server}. ")
                throw new ResourceModelSourceException("cloud link not found for server: " + server.attributes['name'])
            }
            def cloud_id = cloud_href.split("/").last()
            def InstanceResource instance = api.getInstances(cloud_id).get(server.links['current_instance'])
            if (null == instance) {
                logger.error("Failed getting instance for server: ${server.links['self']}. current_instance: "
                        + server.links['current_instance'])
                throw new ResourceModelSourceException(
                        "Failed getting instance for server " + server.links['self'] + ". current_instance: "
                                + server.links['current_instance'])
            }
            // Extra precaution: only process instances that are also in state, operational.
            if ("operational".equalsIgnoreCase(instance.attributes['state'])) {

                System.out.println("DEBUG: Populating node for server current_instance: ${instance.attributes['name']}")
                logger.info("Populating node for server current_instance: ${instance.attributes['name']}")

                def nodename = instance.attributes['name'] + " " + instance.attributes['resource_uid']
                def NodeEntryImpl newNode = createNode(nodename)

                server.populate(newNode)

                instance.populate(newNode)

                populateInstanceResources(api, instance, newNode)

                // example url to gui: https://us-3.rightscale.com/acct/71655/servers/952109003
                def String editUrl = configuration.getProperty(RightscaleNodesFactory.ENDPOINT) +
                        "/acct/" + configuration.getProperty(RightscaleNodesFactory.ACCOUNT) +
                        "/servers/" + server.getId()
                newNode.setAttribute('editUrl', editUrl)
                newNode.setAttribute('plugin:last-refresh',new Date(System.currentTimeMillis()).toString())

                // Add the node to the result.
                nodeset.putNode(newNode)
                logger.info("Populated node: " + newNode.getNodename() + " for server: ${server.links['self']}")
                System.out.println("DEBUG: Populated node: " + newNode.getNodename() + " for server: ${server.links['self']}")
            }

        }
        def duration = (System.currentTimeMillis() - starttime)
        System.println("DEBUG: Populate server nodes ended. (nodes: ${nodeset.getNodes().size()}, duration: ${duration})")
        logger.info("Populate server nodes ended. (nodes: ${nodeset.getNodes().size()}, duration: ${duration})")
        timer.stop()

        return nodeset
    }

    /**
     * Generate Nodes from Instances launched by ServerArrays.
     * @param api the RightscaleQuery
     * @return a new INodeSet of nodes for each instance in the query result.
     */
    INodeSet populateServerArrayNodes(RightscaleAPI api) {
        def long starttime = System.currentTimeMillis()
        def timer = metrics.timer(MetricRegistry.name(RightscaleNodes, 'populateServerArrayNodes.duration')).time()

        /**
         * Create a nodeset for query the result.
         */
        def nodeset = new NodeSetImpl();
        /**
         * List the ServerArrays
         */
        def serverArrays = api.getServerArrays().values()
        System.out.println("DEBUG: Populating nodes for server arrays. (count: ${serverArrays.size()})")
        logger.info("Populating nodes for server arrays. (count: ${serverArrays.size()})")
        serverArrays.each { serverArray ->
            def server_array_id = serverArray.getId()
            logger.info("Populating instances for server array: " + serverArray.attributes['name'])
            println("DEBUG: Populating instances for server array: " + serverArray.attributes['name'])
            /**
             * Get the Instances for this array
             */
            def instances = api.getServerArrayInstances(server_array_id)
            // Only include instances that are operational.
            def operationalInstances = instances.values().findAll { "operational".equalsIgnoreCase(it.attributes['state']) }
            logger.info("Populating nodes for operational instances. (count: ${operationalInstances.size()})")
            System.out.println("DEBUG: Populating nodes for operational instances. (count: ${operationalInstances.size()})")

            operationalInstances.each { instance ->
                /**
                 * Populate the Node entry with the instance data.
                 */
                System.out.println("DEBUG: Populating node for instance: ${instance.attributes['name']}")
                logger.info("Populating node for instance: ${instance.attributes['name']}")

                def nodename = instance.attributes['name'] + " " + instance.attributes['resource_uid']

                def NodeEntryImpl newNode = createNode(nodename)

                instance.populate(newNode)

                populateInstanceResources(api, instance, newNode)

                serverArray.populate(newNode)

                // Edit URL is to the server array Instances tab.
                // https://us-3.rightscale.com/acct/71655/server_arrays/226176003
                def String editUrl = configuration.getProperty(RightscaleNodesFactory.ENDPOINT) +
                        "/acct/" + configuration.getProperty(RightscaleNodesFactory.ACCOUNT) +
                        "/server_arrays/" + server_array_id + "#instances"
                newNode.setAttribute('editUrl', editUrl)
                newNode.setAttribute('plugin:last-refresh',new Date(System.currentTimeMillis()).toString())

                nodeset.putNode(newNode)
                System.out.println("DEBUG: Populated node for server array: ${serverArray.attributes['name']}, instance: ${instance.attributes['name']}")
                logger.info("Populated node for server array ${serverArray.attributes['name']}, instance: ${instance.attributes['name']}")
            }
        }
        def duration = (System.currentTimeMillis() - starttime)
        System.println("DEBUG: Populated nodes for ${serverArrays.size()} server arrays. (nodes: ${nodeset.getNodes().size()}, duration: ${duration})")
        logger.info("Populated nodes for ${serverArrays.size()} server arrays. (nodes: ${nodeset.getNodes().size()}, duration: ${duration})")
        timer.stop()

        return nodeset;
    }

    /**
     * Convenience method to create a new Node with defaults.
     * @param name Name of the node
     * @return a new Node
     */
    NodeEntryImpl createNode(final String name) {
        NodeEntryImpl newNode = new NodeEntryImpl(name);
        newNode.setUsername(configuration.getProperty(RightscaleNodesFactory.USERNAME))
        // Based on convention.
        newNode.setOsName("Linux");   // - Hard coded default.
        newNode.setOsFamily("unix");  // - "
        newNode.setOsArch("x86_64");  // - "
        return newNode
    }

    /**
     *
     * @param api
     * @param instance
     * @param newNode
     */
    void populateInstanceResources(RightscaleAPI api, InstanceResource instance, NodeEntryImpl newNode) {

        def long starttime = System.currentTimeMillis()
        def timer = metrics.timer(MetricRegistry.name(RightscaleNodes, 'populateInstanceResources.duration')).time()

        def cloud_id = instance.links['cloud'].split("/").last()

        instance.links.each { rel, href ->
            switch (rel) {
                case "cloud":
                    def cloud = api.getClouds().get(instance.links['cloud'])
                    cloud.populate(newNode)
                    break
                case "datacenter":
                    def datacenter = api.getDatacenters(cloud_id).get(instance.links['datacenter'])
                    if (null != datacenter) {
                        datacenter.populate(newNode)
                    }
                    break
                case "deployment":
                    def deployment = api.getDeployments().get(instance.links['deployment'])
                    if (null != deployment) deployment.populate(newNode)
                    break
                case "image":
                    def image = api.getImages(cloud_id).get(instance.links['image'])
                    if (null != image) {
                        image.populate(newNode)
                    }
                    break
                case "inputs":
                    def inputs = api.getInputs(instance.links['inputs'])
                    inputs.values().each { input ->
                        if (input.attributes['name'].matches(configuration.getProperty(RightscaleNodesFactory.INPUT_PATT))) {
                            input.populate(newNode)
                            logger.info("Populated node attribute for input: ${input.attributes['name']}")
                            System.out.println("DEBUG: Populated node attribute for input: ${input.attributes['name']}")
                        } else {
                            logger.info("Ignored input ${input.attributes['name']}. Did not match: " + configuration.getProperty(RightscaleNodesFactory.INPUT_PATT))
                            System.out.println("DEBUG: Ignored input ${input.attributes['name']}. Did not match: " + configuration.getProperty(RightscaleNodesFactory.INPUT_PATT))
                        }
                    }
                    break;
                case "instance_type":
                    def instance_type = api.getInstanceTypes(cloud_id).get(instance.links['instance_type'])
                    if (null != instance_type) {
                        instance_type.populate(newNode)
                    }
                    break
                case "server_template":
                    def server_template = api.getServerTemplates().get(instance.links['server_template'])
                    if (null != server_template) {
                        server_template.populate(newNode)
                    }
                    break
                case "ssh_key":
                    def ssh_key = api.getSshKeys(cloud_id).get(instance.links['ssh_key'])
                    if (null != ssh_key) {
                        ssh_key.populate(newNode)
                    }
                    break
            }
        }
        System.out.println("DEBUG: Populating node tags for instance: " + instance.attributes['name'])
        logger.info("Populating node tags for instance: " + instance.attributes['name'])
        def tags = api.getTags(instance.links['self'])
        tags.values().each { TagsResource tag ->
            tag.attributes['tags'].split(",").each { name ->

                if (name.matches(configuration.getProperty(RightscaleNodesFactory.TAG_PATT))) {

                    /**
                     * Generate an attribute if the tag contains an equal sign.
                     */
                    if (Boolean.parseBoolean(configuration.getProperty(RightscaleNodesFactory.TAG_ATTR)) && tag.hasAttributeForm(name)) {

                        tag.setAttribute(name, newNode)
                        System.out.println("DEBUG: Populated node attribute for tag: ${name}.")
                        logger.info("Populated node attribute for tag: ${name}.")
                    } else {
                        RightscaleResource.setTag(name, newNode)
                        logger.info("Populated  node tag: ${name}")
                        System.out.println("DEBUG: Populated node tag: ${name}")
                    }

                } else {
                    logger.info("Ignoring tag ${name}. Did not match ${configuration.getProperty(RightscaleNodesFactory.TAG_PATT)}")
                    System.out.println("DEBUG: Ignoring tag ${name}. Did not match ${configuration.getProperty(RightscaleNodesFactory.TAG_PATT)}")
                }
            }
        }



        def duration = (System.currentTimeMillis() - starttime)
        timer.stop()
    }
}
