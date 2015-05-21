package org.elasticsearch.service.graphite;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.admin.cluster.node.stats.NodeStats;
import org.elasticsearch.action.admin.indices.stats.CommonStatsFlags;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.collect.Lists;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.index.shard.service.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.NodeIndicesStats;
import org.elasticsearch.node.service.NodeService;

import java.util.List;
import java.util.regex.Pattern;

public class GraphiteService extends AbstractLifecycleComponent<GraphiteService> {

    private final ClusterService clusterService;
    private final IndicesService indicesService;
    private NodeService nodeService;
    private final String graphiteHost;
    private final Integer graphitePort;
    private final TimeValue graphiteRefreshInternal;
    private final String graphitePrefix;
    private Pattern graphiteInclusionRegex;
    private Pattern graphiteExclusionRegex;

    private volatile Thread graphiteReporterThread;
    private volatile boolean closed;

    @Inject public GraphiteService(Settings settings, ClusterService clusterService, IndicesService indicesService,
                                   NodeService nodeService) {
        super(settings);
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.nodeService = nodeService;
        graphiteRefreshInternal = settings.getAsTime("metrics.graphite.every", TimeValue.timeValueMinutes(1));
        graphiteHost = settings.get("metrics.graphite.host");
        graphitePort = settings.getAsInt("metrics.graphite.port", 2003);
        graphitePrefix = settings.get("metrics.graphite.prefix", "elasticsearch" + "." + settings.get("cluster.name"));
        String graphiteInclusionRegexString = settings.get("metrics.graphite.include");
        if (graphiteInclusionRegexString != null) {
            graphiteInclusionRegex = Pattern.compile(graphiteInclusionRegexString);
        }
        String graphiteExclusionRegexString = settings.get("metrics.graphite.exclude");
        if (graphiteExclusionRegexString != null) {
            graphiteExclusionRegex = Pattern.compile(graphiteExclusionRegexString);
        }
    }

    @Override
    protected void doStart() throws ElasticsearchException {
        if (graphiteHost != null && graphiteHost.length() > 0) {
            graphiteReporterThread = EsExecutors.daemonThreadFactory(settings, "graphite_reporter").newThread(new GraphiteReporterThread(graphiteInclusionRegex, graphiteExclusionRegex));
            graphiteReporterThread.start();
            StringBuilder sb = new StringBuilder();
            if (graphiteInclusionRegex != null) sb.append("include [").append(graphiteInclusionRegex).append("] ");
            if (graphiteExclusionRegex != null) sb.append("exclude [").append(graphiteExclusionRegex).append("] ");
            logger.info("Graphite reporting triggered every [{}] to host [{}:{}] with metric prefix [{}] {}", graphiteRefreshInternal, graphiteHost, graphitePort, graphitePrefix, sb);
        } else {
            logger.error("Graphite reporting disabled, no graphite host configured");
        }
    }

    @Override
    protected void doStop() throws ElasticsearchException {
        if (closed) {
            return;
        }
        if (graphiteReporterThread != null) {
            graphiteReporterThread.interrupt();
        }
        closed = true;
        logger.info("Graphite reporter stopped");
    }

    @Override
    protected void doClose() throws ElasticsearchException {}

    public class GraphiteReporterThread implements Runnable {

        private final Pattern graphiteInclusionRegex;
        private final Pattern graphiteExclusionRegex;

        public GraphiteReporterThread(Pattern graphiteInclusionRegex, Pattern graphiteExclusionRegex) {
            this.graphiteInclusionRegex = graphiteInclusionRegex;
            this.graphiteExclusionRegex = graphiteExclusionRegex;
        }

        public void run() {
            logger.trace("run(), (closed = {})", closed);
            while (!closed) {
                try {
                    DiscoveryNode node = clusterService.localNode();
                    boolean isClusterStarted = clusterService.lifecycleState().equals(Lifecycle.State.STARTED);
                    logger.trace("cycle (isClusterStarted = {}, node.isMasterNode = {})",
                        isClusterStarted, node == null ? "<null>" : node.isMasterNode());
                    if (isClusterStarted && node != null && node.isMasterNode()) {
                        logger.trace("getting index stats...");
                        NodeIndicesStats nodeIndicesStats = indicesService.stats(false);
                        CommonStatsFlags commonStatsFlags = new CommonStatsFlags().clear();
                        logger.trace("getting node stats...");
                        NodeStats nodeStats = nodeService.stats(commonStatsFlags, true, true, true, true, true, true, true, true, true);
                        logger.trace("getting shards...");
                        List<IndexShard> indexShards = getIndexShards(indicesService);

                        logger.trace("creating reporter...");
                        GraphiteReporter graphiteReporter = new GraphiteReporter(graphiteHost, graphitePort, graphitePrefix,
                            nodeIndicesStats, indexShards, nodeStats, graphiteInclusionRegex, graphiteExclusionRegex);
                        logger.trace("reporting...");
                        graphiteReporter.run();
                    } else {
                        if (node != null) {
                            logger.debug("[{}]/[{}] is not master node, not triggering update", node.getId(), node.getName());
                        }
                    }
                } catch (Throwable e) {
                    logger.error("unexpected exception on cycle", e);
                }

                try {
                    Thread.sleep(graphiteRefreshInternal.millis());
                } catch (InterruptedException e1) {
                    continue;
                }
            }
            logger.trace("ending run(), (closed = {})", closed);
        }

        private List<IndexShard> getIndexShards(IndicesService indicesService) {
            List<IndexShard> indexShards = Lists.newArrayList();
            for (String indexName : indicesService.indices().keySet()) {
                IndexService indexService = indicesService.indexServiceSafe(indexName);
                for (int shardId : indexService.shardIds()) {
                    indexShards.add(indexService.shard(shardId));
                }
            }
            return indexShards;
        }
    }
}
