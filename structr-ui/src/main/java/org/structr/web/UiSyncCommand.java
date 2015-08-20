package org.structr.web;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.neo4j.graphdb.GraphDatabaseService;
import org.structr.common.error.FrameworkException;
import org.structr.core.GraphObject;
import org.structr.core.app.App;
import org.structr.core.app.StructrApp;
import org.structr.core.graph.MaintenanceCommand;
import org.structr.core.graph.NodeInterface;
import org.structr.core.graph.NodeServiceCommand;
import org.structr.core.graph.RelationshipInterface;
import org.structr.core.graph.SyncCommand;
import org.structr.core.graph.Tx;
import org.structr.rest.resource.MaintenanceParameterResource;
import org.structr.web.entity.FileBase;
import org.structr.web.entity.Folder;
import org.structr.web.entity.dom.DOMNode;
import org.structr.web.entity.dom.Page;
import org.structr.web.entity.dom.ShadowDocument;

/**
 *
 * @author Christian Morgner
 */
public class UiSyncCommand extends NodeServiceCommand implements MaintenanceCommand {

	private static final Logger logger = Logger.getLogger(UiSyncCommand.class.getName());

	static {

		MaintenanceParameterResource.registerMaintenanceCommand("syncUi", UiSyncCommand.class);
	}

	@Override
	public void execute(final Map<String, Object> attributes) throws FrameworkException {

		final String mode = (String)attributes.get("mode");
		if (mode != null) {

			final String fileName = (String)attributes.get("file");
			if (fileName != null) {

				if ("export".equals(mode)) {

					doExport(fileName);
				}

				if ("import".equals(mode)) {

					doImport(fileName);
				}

			} else {

				throw new FrameworkException(400, "Please specify file name using the file parameter.");
			}

		} else {

			throw new FrameworkException(400, "Please specify mode, must be one of (import|export)");
		}
	}

	@Override
	public boolean requiresEnclosingTransaction() {
		return false;
	}

	// ----- private methods -----
	private void doExport(final String fileName) throws FrameworkException {

		// collect all nodes etc that belong to the frontend (including files)
		// and export them to the given output file
		final Set<RelationshipInterface> rels = new LinkedHashSet<>();
		final Set<NodeInterface> nodes        = new LinkedHashSet<>();
		final Set<String> filePaths           = new LinkedHashSet<>();
		final App app                         = StructrApp.getInstance();

		try (final Tx tx = app.tx()) {

			// collect folders that are marked for export
			for (final Folder folder : app.nodeQuery(Folder.class).and(Folder.includeInFrontendExport, true).getAsList()) {

				collectDataRecursively(app, folder, nodes, rels, filePaths);
			}

			// collect pages (including files, shared components etc.)
			for (final Page page : app.nodeQuery(Page.class).getAsList()) {

				collectDataRecursively(app, page, nodes, rels, filePaths);
			}

			SyncCommand.exportToFile(fileName, nodes, rels, filePaths, true);

			tx.success();
		}
	}

	private void doImport(final String fileName) throws FrameworkException {

		final App app                      = StructrApp.getInstance();
		final GraphDatabaseService graphDb = app.getGraphDatabaseService();

		SyncCommand.importFromFile(graphDb, securityContext, fileName, true);

		// import done, now the ShadowDocument needs some special care. :(
		try (final Tx tx = app.tx()) {

			final List<ShadowDocument> shadowDocuments = app.nodeQuery(ShadowDocument.class).includeDeletedAndHidden().getAsList();
			if (shadowDocuments.size() > 1) {

				final List<DOMNode> collectiveChildren = new LinkedList<>();

				// sort by node id (higher node ID is newer entity)
				Collections.sort(shadowDocuments, new Comparator<ShadowDocument>() {

					@Override
					public int compare(final ShadowDocument t1, final ShadowDocument t2) {
						return t2.getNodeId().compareTo(t1.getNodeId());
					}
				});

				final ShadowDocument previousShadowDoc = shadowDocuments.get(0);
				final ShadowDocument newShadowDoc      = shadowDocuments.get(1);

				// collect children of both shadow documents
				collectiveChildren.addAll(previousShadowDoc.getProperty(Page.elements));
				collectiveChildren.addAll(newShadowDoc.getProperty(Page.elements));

				// delete old shadow document
				app.delete(previousShadowDoc);

				// add children to new shadow document
				newShadowDoc.setProperty(Page.elements, collectiveChildren);
			}

			tx.success();
		}
	}

	private void collectDataRecursively(final App app, final GraphObject root, final Set<NodeInterface> nodes, final Set<RelationshipInterface> rels, final Set<String> files) throws FrameworkException {

		if (root.isNode()) {

			final NodeInterface node = root.getSyncNode();
			if (node instanceof FileBase) {

				final String fileUuid = node.getUuid();
				files.add(fileUuid);
			}

			// add node to set, recurse if not already present
			if (nodes.add(node)) {

				final List<GraphObject> syncData = node.getSyncData();
				if (syncData != null) {

					for (final GraphObject obj : syncData) {

						// syncData can contain null objects!
						if (obj != null) {

							collectDataRecursively(app, obj, nodes, rels, files);
						}
					}

				} else {

					logger.log(Level.WARNING, "Node {0} returned null syncData!", node);
				}
			}


		} else if (root.isRelationship()) {

			final RelationshipInterface rel = root.getSyncRelationship();

			// add node to set, recurse if not already present
			if (rels.add(rel)) {

				final List<GraphObject> syncData = rel.getSyncData();
				if (syncData != null) {

					for (final GraphObject obj : syncData) {

						// syncData can contain null objects!
						if (obj != null) {

							collectDataRecursively(app, obj, nodes, rels, files);
						}
					}

				} else {

					logger.log(Level.WARNING, "Relationship {0} returned null syncData!", rel);
				}
			}
		}
	}
}