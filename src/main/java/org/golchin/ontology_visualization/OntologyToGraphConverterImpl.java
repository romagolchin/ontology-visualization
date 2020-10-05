package org.golchin.ontology_visualization;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.graphstream.graph.Edge;
import org.graphstream.graph.Graph;
import org.graphstream.graph.Node;
import org.graphstream.graph.implementations.MultiGraph;
import org.semanticweb.owlapi.model.*;
import owltools.graph.OWLGraphEdge;
import owltools.graph.OWLGraphWrapper;
import owltools.graph.OWLQuantifiedProperty;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.golchin.ontology_visualization.ConversionUtils.addNodeIfNecessary;

public class OntologyToGraphConverterImpl implements OntologyToGraphConverter {
    public static final Set<Boolean> BOOLEANS = ImmutableSet.of(Boolean.TRUE, Boolean.FALSE);
    public static final Parameter<Boolean> MULTIPLY_DATATYPES =
            new Parameter<>("multipleNodesForDatatype", "Use multiple nodes for a datatype", BOOLEANS);
    public static final Parameter<Boolean> MERGE_EQUIVALENT =
            new Parameter<>("mergeEquivalentClasses", "Merge equivalent classes", BOOLEANS);
    private final int minDegree;
    /**
     * If true, merge equivalent classes into one node, otherwise denote equivalence with undirected edges
     */
    private final boolean mergeEquivalentClasses;
    /**
     * If true, create a node for each occurrence of a datatype, otherwise a single one
     */
    private final boolean multipleNodesForDatatype;
    private final Map<Parameter<?>, Object> parameterValues;
    private final AtomicInteger edgeCounter = new AtomicInteger();
    private final AtomicInteger nodeCounter = new AtomicInteger();

    public OntologyToGraphConverterImpl() {
        this(0, Collections.emptyMap());
    }

    public OntologyToGraphConverterImpl(int minDegree, Map<Parameter<?>, Object> parameterValues) {
        this.minDegree = minDegree;
        this.parameterValues = parameterValues;
        mergeEquivalentClasses = (boolean) parameterValues.getOrDefault(MERGE_EQUIVALENT, true);
        multipleNodesForDatatype = (boolean) parameterValues.getOrDefault(MULTIPLY_DATATYPES, true);
    }

    public OntologyToGraphConverterImpl(int minDegree, boolean mergeEquivalentClasses, boolean multipleNodesForDatatype) {
        this(minDegree,
                ImmutableMap.of(MERGE_EQUIVALENT, mergeEquivalentClasses, MULTIPLY_DATATYPES, multipleNodesForDatatype));
    }

    private void traverseEquivalentClasses(OWLOntology ontology,
                                           OWLClass initialClass,
                                           String id,
                                           Map<OWLObject, EquivalentClassesSet> owlObjectsToIds) {
        if (owlObjectsToIds.containsKey(initialClass))
            return;
        EquivalentClassesSet classesSet = new EquivalentClassesSet(id);
        classesSet.nodeIds.add(id);
        traverseEquivalentClasses(ontology, initialClass, classesSet, owlObjectsToIds);
    }

    private void traverseEquivalentClasses(OWLOntology ontology,
                                           OWLClass initialClass,
                                           EquivalentClassesSet classesSet,
                                           Map<OWLObject, EquivalentClassesSet> owlObjectsToIds) {
        owlObjectsToIds.put(initialClass, classesSet);
        classesSet.nodeIds.add(getOWLObjectId(initialClass));
        for (OWLEquivalentClassesAxiom axiom : ontology.getEquivalentClassesAxioms(initialClass)) {
            for (OWLClass namedClass : axiom.getNamedClasses()) {
                EquivalentClassesSet oldClassesSet = owlObjectsToIds.get(namedClass);
                if (oldClassesSet == null) {
                    traverseEquivalentClasses(ontology, namedClass, classesSet, owlObjectsToIds);
                } else if (!oldClassesSet.representativeId.equals(classesSet.representativeId)) {
                    // merge neighbor class to current one
                    owlObjectsToIds.put(namedClass, classesSet);
                    classesSet.nodeIds.addAll(oldClassesSet.nodeIds);
                }
            }
        }
    }

    @Override
    public MultiGraph convert(OWLOntology ontology) {
        OWLGraphWrapper graphWrapper = new OWLGraphWrapper(ontology);
        MultiGraph graph = new MultiGraph("g");
        Map<OWLObject, EquivalentClassesSet> owlObjectsToIds = new HashMap<>();
        Set<OWLObject> allOWLObjects = graphWrapper.getAllOWLObjects();
        convertClasses(ontology, graph, owlObjectsToIds, allOWLObjects);
        convertDataProperties(ontology, graph, owlObjectsToIds);
        for (OWLObject owlObject : allOWLObjects) {
            if (owlObject instanceof OWLObjectProperty) {
                convertDomainRangeAxioms(ontology, graph, owlObjectsToIds, (OWLObjectProperty) owlObject);
            }
            if (owlObject instanceof OWLEntity) {
                OWLEntity entity = (OWLEntity) owlObject;
                Set<OWLGraphEdge> outgoingEdges = graphWrapper.getOutgoingEdges(owlObject);
                for (OWLGraphEdge outgoingEdge : outgoingEdges) {
                    OWLObject target = outgoingEdge.getTarget();
                    if (target instanceof OWLEntity) {
                        String label = outgoingEdge.getQuantifiedPropertyList()
                                .stream()
                                .map(owlQuantifiedProperty -> {
                                    OWLObjectProperty property = owlQuantifiedProperty.getProperty();
                                    OWLQuantifiedProperty.Quantifier quantifier = owlQuantifiedProperty.getQuantifier();
                                    if (property == null)
                                        return quantifier.toString();
                                    return property.getIRI().getRemainder().orNull() + " " + quantifier;
                                })
                                .collect(Collectors.joining(", "));
                        addEdge(ontology, graph, getEdgeId(), owlObjectsToIds, entity, target, label);
                    }
                }
            }
        }
        for (EquivalentClassesSet classesSet : new HashSet<>(owlObjectsToIds.values())) {
            if (classesSet.nodeIds.size() > 1) {
                if (mergeEquivalentClasses) {
                    mergeEquivalentClasses(graph, classesSet);
                } else {
                    createEquivalenceEdges(graph, classesSet);
                }
            }
        }
        List<Node> nodesToRemove = graph.nodes()
                .filter(n -> n.getDegree() < minDegree)
                .collect(Collectors.toList());
        for (Node node : nodesToRemove) {
            graph.removeNode(node);
        }
        return graph;
    }

    private void convertDomainRangeAxioms(OWLOntology ontology,
                                          MultiGraph graph,
                                          Map<OWLObject, EquivalentClassesSet> owlObjectsToIds,
                                          OWLObjectProperty property) {
        Set<OWLObjectPropertyDomainAxiom> domainAxioms = ontology.getObjectPropertyDomainAxioms(property);
        Set<OWLObjectPropertyRangeAxiom> rangeAxioms = ontology.getObjectPropertyRangeAxioms(property);
        for (OWLObjectPropertyDomainAxiom domainAxiom : domainAxioms) {
            for (OWLObjectPropertyRangeAxiom rangeAxiom : rangeAxioms) {
                OWLClassExpression domain = domainAxiom.getDomain();
                OWLClassExpression range = rangeAxiom.getRange();
                if ((domain instanceof OWLClass) && (range instanceof OWLClass)) {
                    String id = getEdgeId();
                    String label = property.getIRI().getRemainder().orNull();
                    addEdge(ontology, graph, id, owlObjectsToIds, domain.asOWLClass(), range.asOWLClass(), label);
                }
            }
        }
    }

    private void convertDataProperties(OWLOntology ontology, MultiGraph graph, Map<OWLObject, EquivalentClassesSet> owlObjectsToIds) {
        for (OWLDataProperty property : ontology.getDataPropertiesInSignature()) {
            Set<OWLDataPropertyDomainAxiom> domainAxioms = ontology.getDataPropertyDomainAxioms(property);
            Set<OWLDataPropertyRangeAxiom> rangeAxioms = ontology.getDataPropertyRangeAxioms(property);
            for (OWLDataPropertyDomainAxiom domainAxiom : domainAxioms) {
                for (OWLDataPropertyRangeAxiom rangeAxiom : rangeAxioms) {
                    OWLClassExpression domain = domainAxiom.getDomain();
                    OWLDataRange range = rangeAxiom.getRange();
                    String label = property.getIRI().getRemainder().orNull();
                    addEdge(ontology, graph, getEdgeId(), owlObjectsToIds, domain, range, label);
                }
            }
        }
    }

    private void convertClasses(OWLOntology ontology, MultiGraph graph, Map<OWLObject, EquivalentClassesSet> owlObjectsToIds, Set<OWLObject> allOWLObjects) {
        for (OWLObject owlObject : allOWLObjects) {
            if (owlObject instanceof OWLClass) {
                OWLClass owlClass = (OWLClass) owlObject;
                Node node = graph.addNode(owlClass.toStringID());
                String label = owlClass.getIRI().getRemainder().orNull();
                node.setAttribute("label", label);
                ConversionUtils.addToAttribute(node, "ids", node.getId());
                traverseEquivalentClasses(ontology, owlClass, node.getId(), owlObjectsToIds);
            }
        }
    }

    private String getEdgeId() {
        return "edge_" + edgeCounter.getAndIncrement();
    }

    private void mergeEquivalentClasses(Graph graph, EquivalentClassesSet classesSet) {
        Node theNode = graph.getNode(classesSet.representativeId);
        List<Node> nodesToRemove = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        for (String nodeId : classesSet.nodeIds) {
            Node node = graph.getNode(nodeId);
            Object label = node.getAttribute("label");
            labels.add(String.valueOf(label));
            if (!Objects.equals(nodeId, classesSet.representativeId)) {
                nodesToRemove.add(node);
                node.edges().forEach(edge -> {
                    Edge copy = copyEdge(graph, theNode, nodeId, node, edge);
                    copy.setAttribute("label", edge.getAttribute("label"));
                });
                ConversionUtils.addToAttribute(theNode, "ids", nodeId);
            }
        }
        for (Node node : nodesToRemove) {
            graph.removeNode(node);
        }
        theNode.setAttribute("label", String.join("\n", labels));
    }

    private void createEquivalenceEdges(Graph graph, EquivalentClassesSet equivalentClassesSet) {
        List<Node> equivalentNodes = equivalentClassesSet.nodeIds.stream()
                .map(graph::getNode)
                .collect(Collectors.toList());
        for (Node node : equivalentNodes) {
            for (Node otherNode : equivalentNodes) {
                if (node != otherNode) {
                    Edge edge = graph.addEdge(getEdgeId(), node, otherNode);
                    edge.setAttribute("label", "equivalent");
                }
            }
        }
    }

    private Edge copyEdge(Graph graph, Node theNode, String nodeId, Node node, Edge edge) {
        String sourceId = edge.getSourceNode().getId();
        String targetId = edge.getTargetNode().getId();
        String edgeId = getEdgeId();
        if (edge.getSourceNode() == edge.getTargetNode() ||
                sourceId.equals(nodeId) && targetId.equals(nodeId)) {
            return graph.addEdge(edgeId, theNode, theNode, true);
        } else if (edge.getSourceNode() == node) {
            return graph.addEdge(edgeId, theNode, edge.getTargetNode(), true);
        } else {
            return graph.addEdge(edgeId, edge.getSourceNode(), theNode, true);
        }
    }

    private void addEdge(OWLOntology ontology,
                        Graph graph,
                        String edgeId,
                        Map<OWLObject, EquivalentClassesSet> owlObjectsToIds,
                        OWLObject source,
                        OWLObject target,
                        String label) {
        if (source instanceof OWLClass) {
            traverseEquivalentClasses(ontology, ((OWLClass) source), getOWLObjectId(source), owlObjectsToIds);
        }
        if (target instanceof OWLClass) {
            traverseEquivalentClasses(ontology, ((OWLClass) target), getOWLObjectId(target), owlObjectsToIds);
        }
        String sourceId = getOWLObjectId(source);
        String sourceLabel = ConversionUtils.getOWLObjectLabel(source);
        String targetId = getOWLObjectId(target);
        String targetLabel = ConversionUtils.getOWLObjectLabel(target);
        addNodeIfNecessary(graph, sourceId, sourceLabel);
        addNodeIfNecessary(graph, targetId, targetLabel);
        graph.addEdge(edgeId, sourceId, targetId, true)
                .setAttribute("label", label);
    }

    private String getOWLObjectId(OWLObject owlObject) {
        if (owlObject instanceof OWLNamedObject) {
            String id = ((OWLNamedObject) owlObject).getIRI().toString();
            if (multipleNodesForDatatype && owlObject instanceof OWLDatatype) {
                OWLDatatype datatype = (OWLDatatype) owlObject;
                if (datatype.isBuiltIn()) {
                    return id + "_" + nodeCounter.getAndIncrement();
                }
            }
            return id;
        }
        return owlObject.toString();
    }

    @Override
    public Map<Parameter<?>, Object> getParameterValues() {
        return parameterValues;
    }

    protected static class EquivalentClassesSet {
        private final Set<String> nodeIds = new HashSet<>();
        private final String representativeId;

        public EquivalentClassesSet(String representativeId) {
            this.representativeId = representativeId;
            nodeIds.add(representativeId);
        }
    }

    @Override
    public String toString() {
        return "Built-in";
    }
}
