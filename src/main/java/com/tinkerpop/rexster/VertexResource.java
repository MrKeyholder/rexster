package com.tinkerpop.rexster;

import com.tinkerpop.blueprints.pgm.Edge;
import com.tinkerpop.blueprints.pgm.Graph;
import com.tinkerpop.blueprints.pgm.Vertex;
import com.tinkerpop.rexster.extension.ExtensionPoint;
import com.tinkerpop.rexster.extension.ExtensionResponse;
import com.tinkerpop.rexster.extension.ExtensionSegmentSet;
import com.tinkerpop.rexster.extension.RexsterExtension;
import com.tinkerpop.rexster.traversals.ElementJSONObject;
import org.apache.log4j.Logger;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;

@Path("/{graphname}/vertices")
@Produces(MediaType.APPLICATION_JSON)
public class VertexResource extends AbstractSubResource {

    private static Logger logger = Logger.getLogger(VertexResource.class);

    public VertexResource() {
        super(null);
    }

    public VertexResource(UriInfo ui, HttpServletRequest req, RexsterApplicationProvider rap) {
        super(rap);
        this.httpServletRequest = req;
        this.uriInfo = ui;
    }

    /**
     * GET http://host/graph/vertices
     * graph.getVertices();
     */
    @GET
    public Response getVertices(@PathParam("graphname") String graphName) {
        Long start = this.getStartOffset();
        Long end = this.getEndOffset();

        try {
            long counter = 0l;
            final JSONArray vertexArray = new JSONArray();

            for (Vertex vertex : this.getRexsterApplicationGraph(graphName).getGraph().getVertices()) {
                if (counter >= start && counter < end) {
                    vertexArray.put(new ElementJSONObject(vertex, this.getReturnKeys(), this.hasShowTypes()));
                }
                counter++;
            }

            this.resultObject.put(Tokens.RESULTS, vertexArray);
            this.resultObject.put(Tokens.TOTAL_SIZE, counter);
            this.resultObject.put(Tokens.QUERY_TIME, this.sh.stopWatch());

        } catch (JSONException ex) {
            logger.error(ex);
            JSONObject error = generateErrorObjectJsonFail(ex);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        } catch (RuntimeException re) {
            logger.error(re);

            JSONObject error = generateErrorObject(re.getMessage(), re);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        }

        return this.addHeaders(Response.ok(this.resultObject)).build();
    }

    /**
     * GET http://host/graph/vertices/id
     * graph.getVertex(id);
     */
    @GET
    @Path("/{id}")
    public Response getSingleVertex(@PathParam("graphname") String graphName, @PathParam("id") String id) {

        try {
            Vertex vertex = this.getRexsterApplicationGraph(graphName).getGraph().getVertex(id);
            if (null != vertex) {
                try {
                    this.resultObject.put(Tokens.RESULTS, new ElementJSONObject(vertex, this.getReturnKeys(), this.hasShowTypes()));
                    this.resultObject.put(Tokens.QUERY_TIME, this.sh.stopWatch());

                    JSONArray extensionsList = getExtensionHypermedia(ExtensionPoint.VERTEX);
                    if (extensionsList != null) {
                        this.resultObject.put(Tokens.LINKS, extensionsList);
                    }

                } catch (JSONException ex) {
                    logger.error(ex);

                    JSONObject error = generateErrorObjectJsonFail(ex);
                    throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
                }
            } else {
                String msg = "Could not find vertex [" + id + "] on graph [" + graphName + "]";
                logger.info(msg);

                JSONObject error = generateErrorObject(msg);
                throw new WebApplicationException(this.addHeaders(Response.status(Status.NOT_FOUND).entity(error)).build());
            }
        } catch (RuntimeException re) {
            logger.error(re);

            JSONObject error = generateErrorObject(re.getMessage(), re);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        }

        return this.addHeaders(Response.ok(this.resultObject)).build();
    }

    @POST
    @Path("/{id}/{extension: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response getGraphExtension(@PathParam("graphname") String graphName, @PathParam("id") String id, JSONObject json) {
        this.setRequestObject(json);
        return this.getGraphExtension(graphName, id);
    }

    @GET
    @Path("/{id}/{extension: (?!direction).+}")
    public Response getGraphExtension(@PathParam("graphname") String graphName, @PathParam("id") String id) {

        Vertex vertex = this.getRexsterApplicationGraph(graphName).getGraph().getVertex(id);

        ExtensionResponse extResponse;
        ExtensionSegmentSet extensionSegmentSet = parseUriForExtensionSegment(graphName, ExtensionPoint.VERTEX);

        // determine if the namespace and extension are enabled for this graph
        RexsterApplicationGraph rag = this.getRexsterApplicationGraph(graphName);

        if (rag.isExtensionAllowed(extensionSegmentSet)) {

            Object returnValue = null;

            // namespace was allowed so try to run the extension
            try {

                // look for the extension as loaded through serviceloader
                RexsterExtension rexsterExtension = findExtension(extensionSegmentSet);

                if (rexsterExtension == null) {
                    // extension was not found for some reason
                    logger.error("The [" + extensionSegmentSet + "] extension was not found for [" + graphName + "].  Check com.tinkerpop.rexster.extension.RexsterExtension file in META-INF.services.");
                    JSONObject error = generateErrorObject(
                            "The [" + extensionSegmentSet + "] extension was not found for [" + graphName + "]");
                    throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
                }

                // look up the method on the extension that needs to be called.
                Method methodToCall = findExtensionMethod(rexsterExtension, ExtensionPoint.VERTEX, extensionSegmentSet.getExtensionMethod());

                if (methodToCall == null) {
                    // extension method was not found for some reason
                    logger.error("The [" + extensionSegmentSet + "] extension was not found for [" + graphName + "].  Check com.tinkerpop.rexster.extension.RexsterExtension file in META-INF.services.");
                    JSONObject error = generateErrorObject(
                            "The [" + extensionSegmentSet + "] extension was not found for [" + graphName + "]");
                    throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
                }

                // found the method...time to do work
                returnValue = invokeExtension(graphName, rexsterExtension, methodToCall, vertex);

            } catch (Exception ex) {
                logger.error(ex);
                JSONObject error = generateErrorObjectJsonFail(ex);
                throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
            }

            if (returnValue instanceof ExtensionResponse) {
                extResponse = (ExtensionResponse) returnValue;

                if (extResponse.isErrorResponse()) {
                    // an error was raised within the extension.  pass it back out as an error.
                    logger.error("The [" + extensionSegmentSet + "] extension raised an error response.");
                    throw new WebApplicationException(this.addHeaders(Response.fromResponse(extResponse.getJerseyResponse())).build());
                }
            } else {
                // extension method is not returning the correct type...needs to be an ExtensionResponse
                logger.error("The [" + extensionSegmentSet + "] extension does not return an ExtensionResponse.");
                JSONObject error = generateErrorObject(
                        "The [" + extensionSegmentSet + "] extension does not return an ExtensionResponse.");
                throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
            }

        } else {
            // namespace was not allowed
            logger.error("The [" + extensionSegmentSet + "] extension was not configured for [" + graphName + "]");
            JSONObject error = generateErrorObject(
                    "The [" + extensionSegmentSet + "] extension was not configured for [" + graphName + "]");
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        }

        return this.addHeaders(Response.fromResponse(extResponse.getJerseyResponse())).build();
    }

    /**
     * GET http://host/graph/vertices/id/direction
     * graph.getVertex(id).get{Direction}Edges();
     */
    @GET
    @Path("/{id}/{direction}")
    public Response getVertexEdges(@PathParam("graphname") String graphName, @PathParam("id") String vertexId, @PathParam("direction") String direction) {
        try {
            Long start = this.getStartOffset();
            Long end = this.getEndOffset();

            long counter = 0l;
            Vertex vertex = this.getRexsterApplicationGraph(graphName).getGraph().getVertex(vertexId);
            JSONArray edgeArray = new JSONArray();

            if (null != vertex) {
                JSONObject tempRequest = this.getNonRexsterRequest();
                if (direction.equals(Tokens.OUT_E) || direction.equals(Tokens.BOTH_E)) {
                    for (Edge edge : vertex.getOutEdges()) {
                        if (this.hasPropertyValues(edge, tempRequest)) {
                            if (counter >= start && counter < end) {
                                edgeArray.put(new ElementJSONObject(edge, this.getReturnKeys(), this.hasShowTypes()));
                            }
                            counter++;
                        }
                    }
                }
                if (direction.equals(Tokens.IN_E) || direction.equals(Tokens.BOTH_E)) {
                    for (Edge edge : vertex.getInEdges()) {
                        if (this.hasPropertyValues(edge, tempRequest)) {
                            if (counter >= start && counter < end) {
                                edgeArray.put(new ElementJSONObject(edge, this.getReturnKeys(), this.hasShowTypes()));
                            }
                            counter++;
                        }
                    }
                }
            } else {
                String msg = "Could not find vertex [" + vertexId + "] on graph [" + graphName + "]";
                logger.info(msg);

                JSONObject error = generateErrorObject(msg);
                throw new WebApplicationException(this.addHeaders(Response.status(Status.NOT_FOUND).entity(error)).build());
            }

            this.resultObject.put(Tokens.RESULTS, edgeArray);
            this.resultObject.put(Tokens.TOTAL_SIZE, counter);
            this.resultObject.put(Tokens.QUERY_TIME, this.sh.stopWatch());

        } catch (JSONException ex) {
            logger.error(ex);

            JSONObject error = generateErrorObjectJsonFail(ex);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        } catch (RuntimeException re) {
            logger.error(re);

            JSONObject error = generateErrorObject(re.getMessage(), re);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        }

        return this.addHeaders(Response.ok(this.resultObject)).build();
    }

    /**
     * POST http://host/graph/vertices
     * graph.addVertex(null);
     */
    @POST
    public Response postNullVertex(@PathParam("graphname") String graphName) {
        return this.postVertex(graphName, null);
    }

    /**
     * POST http://host/graph/vertices/id?key=value
     * Vertex v = graph.addVertex(id);
     * v.setProperty(key,value);
     */
    @POST
    @Path("/{id}")
    public Response postVertex(@PathParam("graphname") String graphName, @PathParam("id") String id) {

        try {
            Graph graph = this.getRexsterApplicationGraph(graphName).getGraph();
            Vertex vertex = graph.getVertex(id);

            if (null == vertex) {
                vertex = graph.addVertex(id);
            } else {
                if (!this.hasElementProperties(this.getRequestObject())) {
                    JSONObject error = generateErrorObjectJsonFail(new Exception("Vertex with id " + id + " already exists"));
                    throw new WebApplicationException(this.addHeaders(Response.status(Status.CONFLICT).entity(error)).build());
                }
            }

            Iterator keys = this.getRequestObject().keys();
            while (keys.hasNext()) {
                String key = keys.next().toString();
                if (!key.startsWith(Tokens.UNDERSCORE)) {
                    vertex.setProperty(key, this.getTypedPropertyValue(this.getRequestObject().getString(key)));
                }
            }

            this.resultObject.put(Tokens.RESULTS, new ElementJSONObject(vertex, this.getReturnKeys(), this.hasShowTypes()));
            this.resultObject.put(Tokens.QUERY_TIME, sh.stopWatch());
        } catch (JSONException ex) {
            logger.error(ex);

            JSONObject error = generateErrorObjectJsonFail(ex);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        }  catch (RuntimeException re) {
            logger.error(re);

            JSONObject error = generateErrorObject(re.getMessage(), re);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        }

        return this.addHeaders(Response.ok(this.resultObject)).build();
    }

    /**
     * DELETE http://host/graph/vertices/id
     * graph.removeVertex(graph.getVertex(id));
     * <p/>
     * DELETE http://host/graph/vertices/id?key1&key2
     * Vertex v = graph.getVertex(id);
     * v.removeProperty(key1);
     * v.removeProperty(key2);
     */
    @DELETE
    @Path("/{id}")
    public Response deleteVertex(@PathParam("graphname") String graphName, @PathParam("id") String id) {
        try {
            final List<String> keys = this.getNonRexsterRequestKeys();
            final Graph graph = this.getRexsterApplicationGraph(graphName).getGraph();
            final Vertex vertex = graph.getVertex(id);
            if (null != vertex) {
                if (keys.size() > 0) {
                    // delete vertex properites
                    for (final String key : keys) {
                        vertex.removeProperty(key);
                    }
                } else {
                    // delete vertex
                    graph.removeVertex(vertex);
                }
            } else {
                final String msg = "Could not find vertex [" + id + "] on graph [" + graphName + "]";
                logger.info(msg);

                JSONObject error = generateErrorObject(msg);
                throw new WebApplicationException(this.addHeaders(Response.status(Status.NOT_FOUND).entity(error)).build());
            }
            this.resultObject.put(Tokens.QUERY_TIME, sh.stopWatch());
        } catch (JSONException ex) {
            logger.error(ex);

            JSONObject error = generateErrorObjectJsonFail(ex);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        } catch (RuntimeException re) {
            logger.error(re);

            JSONObject error = generateErrorObject(re.getMessage(), re);
            throw new WebApplicationException(this.addHeaders(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error)).build());
        }

        return this.addHeaders(Response.ok(this.resultObject)).build();

    }

    /* TODO: WITHOUT CONCURRENT MODIFICATION ERRORS
    @DELETE
    public Response deleteAllVertices() {
        final Graph graph = this.rag.getGraph();
        Iterator<Vertex> itty = graph.getVertices().iterator();
        while(itty.hasNext()) {
            graph.removeVertex(itty.next());
        }

        try {
            this.resultObject.put(GremlinTokens.QUERY_TIME, sh.stopWatch());
        } catch (JSONException ex) {
            logger.error(ex);

            JSONObject error = generateErrorObjectJsonFail(ex);
            throw new WebApplicationException(Response.status(Status.INTERNAL_SERVER_ERROR).entity(error).build());
        }

        return Response.ok(this.resultObject).build();

    }*/
}
