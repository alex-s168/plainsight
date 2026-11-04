package dev.plainsight.editor.selection;

import java.util.UUID;

/** Selects one entity, identified by its UUID so it is found again after reloading. */
public final class EntityNode extends SelectionNode {
	private final UUID entity;
	/** Short type name: "player", "zombie", ... */
	private final String type;
	/** Player name or custom name, if any. */
	private final String entityName;

	public EntityNode(UUID entity, String type, String entityName) {
		this(UUID.randomUUID(), entity, type, entityName);
	}

	public EntityNode(UUID id, UUID entity, String type, String entityName) {
		super(id);
		this.entity = entity;
		this.type = type;
		this.entityName = entityName;
	}

	public UUID entity() {
		return entity;
	}

	public String type() {
		return type;
	}

	public String entityName() {
		return entityName;
	}

	/** Entities are named after themselves ("player: Steve") unless the user renamed the node. */
	@Override
	public String label() {
		return customName() != null ? customName() : type + ": " + entityName;
	}

	@Override
	public String kind() {
		return type;
	}

	@Override
	public long blockCount() {
		return 0;
	}

	@Override
	public EntityNode copy() {
		EntityNode e = new EntityNode(entity, type, entityName);
		e.rename(customName());
		e.visible = visible;
		e.waypoint = waypoint;
		return e;
	}
}
