package com.agentcloud.store;

import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;
import java.time.Instant;

public class InstantArgumentFactory extends AbstractArgumentFactory<Instant> {
    public InstantArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(Instant value, ConfigRegistry config) {
        return (position, statement, ctx) -> statement.setString(position, value != null ? value.toString() : null);
    }
}
