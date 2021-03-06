package org.golchin.ontology_visualization;

import org.apache.log4j.Logger;
import org.graphstream.graph.implementations.MultiGraph;
import org.protege.editor.owl.model.hierarchy.AssertedClassHierarchyProvider;
import org.protege.editor.owl.model.hierarchy.OWLObjectHierarchyProvider;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.model.parameters.OntologyCopy;

import java.util.Collections;

import static org.golchin.ontology_visualization.ConversionUtils.*;

public abstract class OntologyGraphModelBase {
    private static final Logger LOGGER = Logger.getLogger(OntologyGraphModelBase.class);
    protected final OWLOntology ontology;
    protected final MultiGraph graph = new MultiGraph("mg");
    protected final OWLObjectHierarchyProvider<OWLClass> provider;

    public OntologyGraphModelBase(OWLOntology ontology) {
        this.ontology = ontology;
        OWLOntologyManager ontologyManager = OWLManager.createOWLOntologyManager();
        try {
            ontologyManager.copyOntology(ontology, OntologyCopy.SHALLOW);
        } catch (OWLOntologyCreationException e) {
            LOGGER.error("Unexpected error while copying ontology", e);
        }
        provider = new AssertedClassHierarchyProvider(ontologyManager);
        provider.setOntologies(Collections.singleton(ontology));
        graph.setAttribute("annotations", ConversionUtils.getAnnotationValues(ontology));
    }

    public String getOWLObjectLabel(OWLObject owlObject) {
        String label = getLabel(ontology, owlObject);
        if (label != null) {
            return label;
        }
        if (owlObject instanceof OWLNamedObject) {
            return ((OWLNamedObject) owlObject).getIRI().getRemainder().orNull();
        }
        return owlObject.toString();
    }

    protected void createEdge(OWLNamedObject source, OWLNamedObject target, String label) {
        String sourceId = source.getIRI().toString();
        String targetId = target.getIRI().toString();
        addNodeIfNecessary(graph, sourceId, getOWLObjectLabel(source), getAnnotationValues(ontology, source));
        addNodeIfNecessary(graph, targetId, getOWLObjectLabel(target), getAnnotationValues(ontology, target));
        String edgeId = getEdgeId();
        boolean noEdgeWithSameLabel = graph.getNode(sourceId).leavingEdges()
                .noneMatch(edge -> edge.getAttribute("label").equals(label) && edge.getTargetNode().getId().equals(targetId));
        if (noEdgeWithSameLabel) {
            graph.addEdge(edgeId, sourceId, targetId, true)
                    .setAttribute("label", label);
        }
    }

    public abstract MultiGraph getGraph();
}
