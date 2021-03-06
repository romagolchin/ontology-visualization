package org.golchin.ontology_visualization;

import org.graphstream.graph.implementations.MultiGraph;
import org.semanticweb.owlapi.model.OWLOntology;

public interface OntologyToGraphConverter {
    MultiGraph convert(OWLOntology ontology);

}
