/*
 *  Copyright (C) 2010-2012 Axel Morgner, structr <structr@structr.org>
 *
 *  This file is part of structr <http://structr.org>.
 *
 *  structr is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  structr is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with structr.  If not, see <http://www.gnu.org/licenses/>.
 */



package org.structr.core.node;

import org.neo4j.gis.spatial.indexprovider.SpatialRecordHits;
import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.NotFoundException;
import org.neo4j.graphdb.index.IndexHits;
import org.neo4j.helpers.collection.Iterables;

import org.structr.common.RelType;
import org.structr.common.SecurityContext;
import org.structr.common.ThreadLocalCommand;
import org.structr.common.error.FrameworkException;
import org.structr.common.error.IdNotFoundToken;
import org.structr.core.Command;
import org.structr.core.Result;
import org.structr.core.Services;
import org.structr.core.entity.*;
import org.structr.core.entity.AbstractNode;
import org.structr.core.entity.GenericNode;
import org.structr.core.module.GetEntityClassCommand;

//~--- JDK imports ------------------------------------------------------------

import java.lang.reflect.Constructor;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

//~--- classes ----------------------------------------------------------------

/**
 * A factory for structr nodes. This class exists because we need a fast
 * way to instantiate and initialize structr nodes, as this is the most-
 * used operation.
 *
 * @author cmorgner
 */
public class NodeFactory<T extends AbstractNode> {

	public static final int DEFAULT_PAGE      = 1;
	public static String RAW_RESULT_COUNT     = "rawResultCount";
	public static final int DEFAULT_PAGE_SIZE = Integer.MAX_VALUE;
	private static final Logger logger        = Logger.getLogger(NodeFactory.class.getName());

	//~--- fields ---------------------------------------------------------

	private ThreadLocalCommand getEntityClassCommand = new ThreadLocalCommand(GetEntityClassCommand.class);
	private Map<Class, Constructor> constructors     = new LinkedHashMap<Class, Constructor>();

	//~--- constructors ---------------------------------------------------

	// private Map<String, Class> nodeTypeCache = new ConcurrentHashMap<String, Class>();
	public NodeFactory() {}

	//~--- methods --------------------------------------------------------

	public AbstractNode createNode(final SecurityContext securityContext, final Node node) throws FrameworkException {

		return createNode(securityContext, node, false, false);

	}

	public AbstractNode createNode(final SecurityContext securityContext, final Node node, final boolean includeDeletedAndHidden, final boolean publicOnly) throws FrameworkException {

		String type     = AbstractNode.Key.type.name();
		String nodeType = node.hasProperty(type)
				  ? (String) node.getProperty(type)
				  : "";

		return createNode(securityContext, node, nodeType, includeDeletedAndHidden, publicOnly);

	}

	public AbstractNode createNode(final SecurityContext securityContext, final Node node, final String nodeType) throws FrameworkException {

		return createNode(securityContext, node, nodeType, false, false);

	}

	public AbstractNode createNode(final SecurityContext securityContext, final Node node, final String nodeType, final boolean includeDeletedAndHidden, final boolean publicOnly)
		throws FrameworkException {

		/*
		 *  caching disabled for now...
		 * AbstractNode cachedNode = null;
		 *
		 * // only look up node in cache if uuid is already present
		 * if(node.hasProperty(AbstractNode.Key.uuid.name())) {
		 *       String uuid = (String)node.getProperty(AbstractNode.Key.uuid.name());
		 *       cachedNode = NodeService.getNodeFromCache(uuid);
		 * }
		 *
		 * if(cachedNode == null) {
		 */
		Class nodeClass      = (Class) getEntityClassCommand.get().execute(nodeType);
		AbstractNode newNode = null;

		if (nodeClass != null) {

			try {

				Constructor constructor = constructors.get(nodeClass);

				if (constructor == null) {

					constructor = nodeClass.getConstructor();

					constructors.put(nodeClass, constructor);

				}

				// newNode = (AbstractNode) nodeClass.newInstance();
				newNode = (AbstractNode) constructor.newInstance();

			} catch (Throwable t) {

				newNode = null;

			}

		}

		if (newNode == null) {

			newNode = new GenericNode();
		}

		newNode.init(securityContext, node);
		newNode.onNodeInstantiation();

		// check access
		if (securityContext.isReadable(newNode, includeDeletedAndHidden, publicOnly)) {

			return newNode;
		}

		return null;
	}

	public Result createNodes(final SecurityContext securityContext, final IndexHits<Node> input, int pageSize, int page) throws FrameworkException {

		return createNodes(securityContext, input, false, false, pageSize, page);

	}

	public Result createNodes(final SecurityContext securityContext, final IndexHits<Node> input, final boolean includeDeletedAndHidden, final boolean publicOnly, int pageSize, int page)
		throws FrameworkException {

		return createNodes(securityContext, input, includeDeletedAndHidden, publicOnly, pageSize, page, null);

	}

	/**
	 * Create structr nodes from the underlying database nodes
	 *
	 * Include only nodes which are readable in the given security context.
	 * If includeDeletedAndHidden is true, include nodes with 'deleted' flag
	 * If publicOnly is true, filter by 'visibleToPublicUsers' flag
	 *
	 * @param securityContext
	 * @param input
	 * @param includeDeletedAndHidden
	 * @param publicOnly
	 * @param pagSize
	 * @param page
	 * @param offsetId
	 * @return
	 */
	public Result createNodes(final SecurityContext securityContext, final IndexHits<Node> input, final boolean includeDeletedAndHidden, final boolean publicOnly, final int pageSize,
				  final int page, final String offsetId)
		throws FrameworkException {

		List<AbstractNode> nodes = new LinkedList<AbstractNode>();
		int position             = 0;
		int count                = 0;
		int offset;

		if (input != null) {
		
			int size                 = input.size();
			// FIXME: IndexHits#size() is not always accurate, see
			// https://github.com/neo4j/community/blob/master/kernel/src/main/java/org/neo4j/graphdb/index/IndexHits.java
			
			// If we have no offsetId and a negative page number,
			// we need to know the exact number of index hits
			// This breaks lazy loading :-(
			if (offsetId == null && page < 0) {

				size = realSize(input);
				offset = size + (page * pageSize);

			} else {

				// may be overwritten later
				offset = (page - 1) * pageSize;
			}

			if (input instanceof SpatialRecordHits) {

				Command graphDbCommand       = Services.command(securityContext, GraphDatabaseCommand.class);
				GraphDatabaseService graphDb = (GraphDatabaseService) graphDbCommand.execute();

				for (Node node : input) {

					Long dbNodeId = null;
					Node realNode = null;

					if (node.hasProperty("id")) {

						dbNodeId = (Long) node.getProperty("id");

						try {

							realNode = graphDb.getNodeById(dbNodeId);

						} catch (NotFoundException nfe) {

							// Should not happen, but it does
							// FIXME: Why does the spatial index return an unknown ID?
							logger.log(Level.SEVERE, "Node with id {0} not found.", dbNodeId);

							for (String key : node.getPropertyKeys()) {

								logger.log(Level.FINE, "{0}={1}", new Object[] { key, node.getProperty(key) });
							}
						}

					}

					if (realNode != null) {

						AbstractNode n = createNode(securityContext, realNode, includeDeletedAndHidden, publicOnly);

						// Check is done in createNode already, so we don't have to do it again
						if (n != null) {    // && isReadable(securityContext, n, includeDeletedAndHidden, publicOnly)) {

							List<AbstractNode> nodesAt = getNodesAt(n);

							size += nodesAt.size();

							for (AbstractNode nodeAt : nodesAt) {

								if (nodeAt != null && securityContext.isReadable(nodeAt, includeDeletedAndHidden, publicOnly)) {

									if (++position > offset) {

										// stop if we got enough nodes
										if (++count > pageSize) {

											return new Result(nodes, size, true, false);
										}

										nodes.add(nodeAt);
									}

								}

							}

						}

					}
				}

				return new Result(nodes, size, true, false);

			} else {

				if (offsetId != null) {

					// We have an offsetId, so first we need to
					// find the node with this uuid to get the offset
					List<AbstractNode> allNodes = new LinkedList();
					int i                       = 0;
					boolean gotOffset           = false;

					for (Node node : input) {

						AbstractNode n = createNode(securityContext, (Node) node, includeDeletedAndHidden, publicOnly);

						allNodes.add(n);

						if (!gotOffset) {

							if (!offsetId.equals(n.getUuid())) {

								i++;

								continue;

							}

							gotOffset = true;
							offset    = page > 0
								    ? i
								    : i + (page * pageSize);

							break;

						}

					}

					if (!gotOffset) {

						throw new FrameworkException("offsetId", new IdNotFoundToken(offsetId));
					}

					for (AbstractNode node : allNodes) {

						// Check is done in createNode already, so we don't have to do it again
						if (node != null) {    // && isReadable(securityContext, n, includeDeletedAndHidden, publicOnly)) {

							if (++position > offset) {

								// stop if we got enough nodes
								if (++count > pageSize) {

									return new Result(nodes, size, true, false);
								}

								nodes.add(node);
							}

						}
					}

					// If we get here, the result was not complete, so we need to iterate
					// through the index result (input) to get more items.
					for (Node node : input) {

						AbstractNode n = createNode(securityContext, (Node) node, includeDeletedAndHidden, publicOnly);

						// Check is done in createNode already, so we don't have to do it again
						if (n != null) {       // && isReadable(securityContext, n, includeDeletedAndHidden, publicOnly)) {

							if (++position > offset) {

								// stop if we got enough nodes
								if (++count > pageSize) {

									return new Result(nodes, size, true, false);
								}

								nodes.add(n);
							}

						}

					}
				} else {

					for (Node node : input) {

						AbstractNode n = createNode(securityContext, (Node) node, includeDeletedAndHidden, publicOnly);

						// Check is done in createNode already, so we don't have to do it again
						if (n != null) {       // && isReadable(securityContext, n, includeDeletedAndHidden, publicOnly)) {

							if (++position > offset) {

								// stop if we got enough nodes
								if (pageSize > 0 && ++count > pageSize) {

									return new Result(nodes, size, true, false);
								}

								nodes.add(n);
							}

						}

					}

				}

				return new Result(nodes, size, true, false);

			}
		}

		return Result.EMPTY_RESULT;

	}

	/**
	 * Create structr nodes from all given underlying database nodes
	 *
	 * @param input
	 * @return
	 */
	public Result createNodes(final SecurityContext securityContext, final Iterable<Node> input) throws FrameworkException {

		List<AbstractNode> nodes = new LinkedList<AbstractNode>();

		if ((input != null) && input.iterator().hasNext()) {

			for (Node node : input) {

				AbstractNode n = createNode(securityContext, node);

				if (n != null) {

					nodes.add(n);
				}

			}

		}

		return new Result(nodes, null, true, false);

	}

	public AbstractNode createDeletedNode(final SecurityContext securityContext, final Node node, final String nodeType) throws FrameworkException {

		Class nodeClass      = (Class) getEntityClassCommand.get().execute(nodeType);
		AbstractNode newNode = null;

		if (nodeClass != null) {

			try {

				Constructor constructor = constructors.get(nodeClass);

				if (constructor == null) {

					constructor = nodeClass.getConstructor();

					constructors.put(nodeClass, constructor);

				}

				// newNode = (AbstractNode) nodeClass.newInstance();
				newNode = (AbstractNode) constructor.newInstance();

			} catch (Throwable t) {

				newNode = null;

			}

		}

		if (newNode == null) {

			newNode = new GenericNode();
		}

		return newNode;

	}

	private int realSize(final Iterable it) {

		if (it instanceof Collection<?>) {

			return ((Collection<?>) it).size();
		} else {

			int c                = 0;
			Iterator<?> iterator = it.iterator();

			while (iterator.hasNext()) {

				iterator.next();

				c++;

			}

			return c;

		}

	}

	//~--- get methods ----------------------------------------------------

	private List<AbstractNode> getNodesAt(final AbstractNode locationNode) {

		List<AbstractNode> nodes = new LinkedList<AbstractNode>();

		for (AbstractRelationship rel : locationNode.getRelationships(RelType.IS_AT, Direction.INCOMING)) {

			nodes.add(rel.getStartNode());
		}

		return nodes;

	}

}
