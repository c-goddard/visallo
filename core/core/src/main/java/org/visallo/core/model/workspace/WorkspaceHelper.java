package org.visallo.core.model.workspace;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.vertexium.*;
import org.vertexium.util.IterableUtils;
import org.visallo.core.exception.VisalloAccessDeniedException;
import org.visallo.core.exception.VisalloException;
import org.visallo.core.model.ontology.OntologyRepository;
import org.visallo.core.model.properties.VisalloProperties;
import org.visallo.core.model.termMention.TermMentionRepository;
import org.visallo.core.model.user.UserRepository;
import org.visallo.core.model.workQueue.Priority;
import org.visallo.core.model.workQueue.WorkQueueRepository;
import org.visallo.core.user.User;
import org.visallo.core.util.SandboxStatusUtil;
import org.visallo.core.util.VisalloLogger;
import org.visallo.core.util.VisalloLoggerFactory;
import org.visallo.web.clientapi.model.Privilege;
import org.visallo.web.clientapi.model.SandboxStatus;
import org.visallo.web.clientapi.model.VisibilityJson;

import java.util.List;

@Singleton
public class WorkspaceHelper {
    private static final VisalloLogger LOGGER = VisalloLoggerFactory.getLogger(WorkspaceHelper.class);
    private final TermMentionRepository termMentionRepository;
    private final UserRepository userRepository;
    private final WorkQueueRepository workQueueRepository;
    private final Graph graph;
    private final OntologyRepository ontologyRepository;
    private final WorkspaceRepository workspaceRepository;
    private String entityHasImageIri;
    private String artifactContainsImageOfEntityIri;

    @Inject
    public WorkspaceHelper(
            final TermMentionRepository termMentionRepository,
            final UserRepository userRepository,
            final WorkQueueRepository workQueueRepository,
            final Graph graph,
            final OntologyRepository ontologyRepository,
            final WorkspaceRepository workspaceRepository
    ) {
        this.termMentionRepository = termMentionRepository;
        this.userRepository = userRepository;
        this.workQueueRepository = workQueueRepository;
        this.graph = graph;
        this.ontologyRepository = ontologyRepository;
        this.workspaceRepository = workspaceRepository;

        this.entityHasImageIri = ontologyRepository.getRelationshipIRIByIntent("entityHasImage");
        if (this.entityHasImageIri == null) {
            LOGGER.warn("'entityHasImage' intent has not been defined. Please update your ontology.");
        }

        this.artifactContainsImageOfEntityIri = ontologyRepository.getRelationshipIRIByIntent("artifactContainsImageOfEntity");
        if (this.artifactContainsImageOfEntityIri == null) {
            LOGGER.warn("'artifactContainsImageOfEntity' intent has not been defined. Please update your ontology.");
        }
    }

    public static String getWorkspaceIdOrNullIfPublish(String workspaceId, boolean shouldPublish, User user) {
        if (shouldPublish) {
            if (user.getPrivileges().contains(Privilege.PUBLISH)) {
                workspaceId = null;
            } else {
                throw new VisalloAccessDeniedException("The publish parameter was sent in the request, but the user does not have publish privilege.", user, "publish");
            }
        } else if (workspaceId == null) {
            throw new VisalloException("workspaceId parameter required");
        }
        return workspaceId;
    }

    public void unresolveTerm(Vertex termMention, Authorizations authorizations) {
        Vertex outVertex = termMentionRepository.findOutVertex(termMention, authorizations);
        if (outVertex == null) {
            return;
        }

        String resolveEdgeId = VisalloProperties.TERM_MENTION_RESOLVED_EDGE_ID.getPropertyValue(termMention, null);
        if (resolveEdgeId != null) {
            Edge resolveEdge = graph.getEdge(resolveEdgeId, authorizations);
            long timestamp = System.currentTimeMillis();
            graph.softDeleteEdge(resolveEdge, authorizations);
            graph.flush();
            workQueueRepository.pushEdgeDeletion(resolveEdge, timestamp, Priority.HIGH);
        }

        termMentionRepository.delete(termMention, authorizations);
        workQueueRepository.pushTextUpdated(outVertex.getId());

        graph.flush();
    }

    public void deleteProperty(Element e, Property property, boolean propertyIsPublic, String workspaceId,
                               Priority priority, Authorizations authorizations) {
        deleteProperty(e, property, propertyIsPublic, workspaceId, priority, false, null, authorizations);
    }

    public void deleteProperty(Element e, Property property, boolean propertyIsPublic, String workspaceId,
                               Priority priority, boolean isElementDeleted, Long beforeElementDeleteTimestamp,
                               Authorizations authorizations) {
        if (propertyIsPublic && workspaceId != null) {
            e.markPropertyHidden(property, new Visibility(workspaceId), authorizations);
        } else {
            e.softDeleteProperty(property.getKey(), property.getName(), property.getVisibility(), authorizations);
        }

        if (e instanceof Vertex) {
            unresolveTermMentionsForProperty((Vertex) e, property, authorizations);
        }

        graph.flush();

        workQueueRepository.pushGraphPropertyQueue(e, property, isElementDeleted, beforeElementDeleteTimestamp, priority);
    }

    public void deleteEdge(
            String workspaceId,
            Edge edge,
            Vertex outVertex,
            @SuppressWarnings("UnusedParameters") Vertex inVertex,
            boolean isPublicEdge,
            Priority priority,
            Authorizations authorizations,
            User user
    ) {
        ensureOntologyIrisInitialized();
        long timestamp = System.currentTimeMillis();

        deleteProperties(edge, workspaceId, priority, true, timestamp, authorizations, user);

        // add the vertex to the workspace so that the changes show up in the diff panel
        workspaceRepository.updateEntityOnWorkspace(workspaceId, edge.getVertexId(Direction.IN), null, null, user);
        workspaceRepository.updateEntityOnWorkspace(workspaceId, edge.getVertexId(Direction.OUT), null, null, user);

        if (isPublicEdge) {
            Visibility workspaceVisibility = new Visibility(workspaceId);

            graph.markEdgeHidden(edge, workspaceVisibility, authorizations);

            if (edge.getLabel().equals(entityHasImageIri)) {
                Property entityHasImage = outVertex.getProperty(VisalloProperties.ENTITY_IMAGE_VERTEX_ID.getPropertyName());
                if (entityHasImage != null) {
                    outVertex.markPropertyHidden(entityHasImage, workspaceVisibility, authorizations);
                    this.workQueueRepository.pushElementImageQueue(outVertex, entityHasImage, priority);
                }
            }

            for (Vertex termMention : termMentionRepository.findByEdgeId(outVertex.getId(), edge.getId(), authorizations)) {
                termMentionRepository.markHidden(termMention, workspaceVisibility, authorizations);
                workQueueRepository.pushTextUpdated(outVertex.getId());
            }

            graph.flush();
        } else {
            graph.softDeleteEdge(edge, authorizations);

            if (edge.getLabel().equals(entityHasImageIri)) {
                Property entityHasImage = outVertex.getProperty(VisalloProperties.ENTITY_IMAGE_VERTEX_ID.getPropertyName());
                if (entityHasImage != null) {
                    outVertex.softDeleteProperty(entityHasImage.getKey(), entityHasImage.getName(), authorizations);
                    this.workQueueRepository.pushElementImageQueue(outVertex, entityHasImage, priority);
                }
            }

            for (Vertex termMention : termMentionRepository.findByEdgeId(outVertex.getId(), edge.getId(), authorizations)) {
                termMentionRepository.delete(termMention, authorizations);
                workQueueRepository.pushTextUpdated(outVertex.getId());
            }

            graph.flush();
        }

        this.workQueueRepository.pushEdgeDeletion(edge, timestamp, Priority.HIGH);
        graph.flush();
    }

    private void ensureOntologyIrisInitialized() {
        if (this.entityHasImageIri == null) {
            this.entityHasImageIri = ontologyRepository.getRelationshipIRIByIntent("entityHasImage");
        }
        if (this.artifactContainsImageOfEntityIri == null) {
            this.artifactContainsImageOfEntityIri = ontologyRepository.getRelationshipIRIByIntent("artifactContainsImageOfEntity");
        }
    }

    private void deleteProperties(Element e, String workspaceId, Priority priority, boolean isElementDeleted, Long beforeDeleteTimestamp, Authorizations authorizations, User user) {
        List<Property> properties = IterableUtils.toList(e.getProperties());
        SandboxStatus[] sandboxStatuses = SandboxStatusUtil.getPropertySandboxStatuses(properties, workspaceId);

        for (int i = 0; i < sandboxStatuses.length; i++) {
            boolean propertyIsPublic = (sandboxStatuses[i] == SandboxStatus.PUBLIC);
            Property property = properties.get(i);
            deleteProperty(e, property, propertyIsPublic, workspaceId, priority, isElementDeleted, beforeDeleteTimestamp, authorizations);
        }
    }

    public void deleteVertex(Vertex vertex, String workspaceId, boolean isPublicVertex, Priority priority, Authorizations authorizations, User user) {
        LOGGER.debug("BEGIN deleteVertex(vertexId: %s, workspaceId: %s, isPublicVertex: %b, user: %s)", vertex.getId(), workspaceId, isPublicVertex, user.getUsername());
        ensureOntologyIrisInitialized();
        long timestamp = System.currentTimeMillis();

        deleteProperties(vertex, workspaceId, priority, true, timestamp, authorizations, user);

        // make sure the entity is on the workspace so that it shows up in the diff panel
        workspaceRepository.updateEntityOnWorkspace(workspaceId, vertex.getId(), null, null, user);

        if (isPublicVertex) {
            Visibility workspaceVisibility = new Visibility(workspaceId);

            graph.markVertexHidden(vertex, workspaceVisibility, authorizations);
            graph.flush();
            workQueueRepository.pushVertexDeletion(vertex, timestamp, Priority.HIGH);
        } else {
            VisibilityJson visibilityJson = VisalloProperties.VISIBILITY_JSON.getPropertyValue(vertex);
            visibilityJson = VisibilityJson.removeFromAllWorkspace(visibilityJson);

            // because we store the current vertex image in a property we need to possibly find that property and change it
            //  if we are deleting the current image.
            LOGGER.debug("change entity image properties");
            for (Edge edge : vertex.getEdges(Direction.BOTH, entityHasImageIri, authorizations)) {
                if (edge.getVertexId(Direction.IN).equals(vertex.getId())) {
                    Vertex outVertex = edge.getVertex(Direction.OUT, authorizations);
                    Property entityHasImage = outVertex.getProperty(VisalloProperties.ENTITY_IMAGE_VERTEX_ID.getPropertyName());
                    outVertex.softDeleteProperty(entityHasImage.getKey(), entityHasImage.getName(), authorizations);
                    workQueueRepository.pushElementImageQueue(outVertex, entityHasImage, priority);
                }
            }

            // because detected objects are currently stored as properties on the artifact that reference the entity
            //   that they are resolved to we need to delete that property
            LOGGER.debug("change artifact contains image of entity");
            for (Edge edge : vertex.getEdges(Direction.BOTH, artifactContainsImageOfEntityIri, authorizations)) {
                for (Property rowKeyProperty : vertex.getProperties(VisalloProperties.ROW_KEY.getPropertyName())) {
                    String multiValueKey = rowKeyProperty.getValue().toString();
                    if (edge.getVertexId(Direction.IN).equals(vertex.getId())) {
                        Vertex outVertex = edge.getVertex(Direction.OUT, authorizations);
                        // remove property
                        VisalloProperties.DETECTED_OBJECT.removeProperty(outVertex, multiValueKey, authorizations);
                        graph.softDeleteEdge(edge, authorizations);
                        workQueueRepository.pushEdgeDeletion(edge, timestamp, Priority.HIGH);
                        workQueueRepository.pushGraphPropertyQueue(
                                outVertex,
                                multiValueKey,
                                VisalloProperties.DETECTED_OBJECT.getPropertyName(),
                                workspaceId,
                                visibilityJson.getSource(),
                                false,
                                null,
                                priority
                        );
                    }
                }
            }

            // because we store term mentions with an added visibility we need to delete them with that added authorizations.
            //  we also need to notify the front-end of changes as well as audit the changes
            LOGGER.debug("unresolve terms");
            for (Vertex termMention : termMentionRepository.findResolvedTo(vertex.getId(), authorizations)) {
                unresolveTerm(termMention, authorizations);
            }

            // because we store workspaces with an added visibility we need to delete them with that added authorizations.
            LOGGER.debug("soft delete edges");
            Authorizations systemAuthorization = userRepository.getAuthorizations(user, WorkspaceRepository.VISIBILITY_STRING, workspaceId);
            Vertex workspaceVertex = graph.getVertex(workspaceId, systemAuthorization);
            for (Edge edge : workspaceVertex.getEdges(vertex, Direction.BOTH, systemAuthorization)) {
                graph.softDeleteEdge(edge, systemAuthorization);
            }

            LOGGER.debug("soft delete vertex");
            graph.softDeleteVertex(vertex, authorizations);
            graph.flush();
            this.workQueueRepository.pushVertexDeletion(vertex, timestamp, Priority.HIGH);
        }

        graph.flush();
        LOGGER.debug("END deleteVertex");
    }

    private void unresolveTermMentionsForProperty(Vertex vertex, Property property, Authorizations authorizations) {
        for (Vertex termMention : termMentionRepository.findResolvedTo(vertex.getId(), authorizations)) {
            String key = VisalloProperties.TERM_MENTION_REF_PROPERTY_KEY.getPropertyValue(termMention);
            String name = VisalloProperties.TERM_MENTION_REF_PROPERTY_NAME.getPropertyValue(termMention);
            String visibility = VisalloProperties.TERM_MENTION_REF_PROPERTY_VISIBILITY.getPropertyValue(termMention);
            if (property.getKey().equals(key) && property.getName().equals(name) &&
                    property.getVisibility().getVisibilityString().equals(visibility)) {
                unresolveTerm(termMention, authorizations);
            }
        }
    }
}
