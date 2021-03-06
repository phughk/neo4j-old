/*
 * Copyright (c) 2002-2018 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.query;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.neo4j.storageengine.api.lock.ResourceType;

class WaitingOnLock extends ExecutingQueryStatus
{
    private final String mode;
    private final ResourceType resourceType;
    private final long[] resourceIds;
    private final long startTimeNanos;

    WaitingOnLock( String mode, ResourceType resourceType, long[] resourceIds, long startTimeNanos )
    {
        this.mode = mode;
        this.resourceType = resourceType;
        this.resourceIds = resourceIds;
        this.startTimeNanos = startTimeNanos;
    }

    @Override
    long waitTimeNanos( long currentTimeNanos )
    {
        return currentTimeNanos - startTimeNanos;
    }

    @Override
    Map<String,Object> toMap( long currentTimeNanos )
    {
        Map<String,Object> map = new HashMap<>();
        map.put( "lockMode", mode );
        map.put( "waitTimeMillis", TimeUnit.NANOSECONDS.toMillis( waitTimeNanos( currentTimeNanos ) ) );
        map.put( "resourceType", resourceType.toString() );
        map.put( "resourceIds", resourceIds );
        return map;
    }

    @Override
    String name()
    {
        return WAITING_STATE;
    }
}
