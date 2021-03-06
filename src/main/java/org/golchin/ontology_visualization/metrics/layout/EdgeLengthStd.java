package org.golchin.ontology_visualization.metrics.layout;

import com.google.common.math.StatsAccumulator;
import org.graphstream.graph.Graph;
import org.graphstream.graph.Node;

import java.awt.geom.Point2D;
import java.util.Comparator;
import java.util.function.Function;

import static org.golchin.ontology_visualization.metrics.MetricUtils.getMeanLength;

public class EdgeLengthStd implements LayoutMetric {
    @Override
    public double calculate(Graph graph, Function<Node, Point2D> vertexToPoint) {
        double meanLength = getMeanLength(graph, vertexToPoint);
        StatsAccumulator statsAccumulator = new StatsAccumulator();
        graph.edges().map(edge -> {
            Point2D sourcePoint = vertexToPoint.apply(edge.getSourceNode());
            Point2D destPoint = vertexToPoint.apply(edge.getTargetNode());
            double distance = sourcePoint.distance(destPoint);
            distance /= meanLength;
            return distance;
        }).forEach(statsAccumulator::add);
        return statsAccumulator.populationStandardDeviation();
    }

    @Override
    public Comparator<Double> getComparator() {
        return Comparator.<Double>naturalOrder().reversed();
    }
}
