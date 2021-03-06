/*
 * This file is part of Spout.
 *
 * Copyright (c) 2011-2012, SpoutDev <http://www.spout.org/>
 * Spout is licensed under the SpoutDev License Version 1.
 *
 * Spout is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the SpoutDev License Version 1.
 *
 * Spout is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the SpoutDev License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://www.spout.org/SpoutDevLicenseV1.txt> for the full license,
 * including the MIT license.
 */
package org.spout.engine.protocol;

import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

import org.jboss.netty.channel.Channel;

import org.spout.api.Spout;
import org.spout.api.datatable.DataMap;
import org.spout.api.datatable.DatatableMap;
import org.spout.api.datatable.GenericDatatableMap;
import org.spout.api.map.DefaultedMap;
import org.spout.api.protocol.Message;
import org.spout.api.protocol.MessageHandler;
import org.spout.api.protocol.NetworkSynchronizer;
import org.spout.api.protocol.NullNetworkSynchronizer;
import org.spout.api.protocol.Protocol;
import org.spout.api.protocol.Session;
import org.spout.engine.SpoutEngine;
import org.spout.engine.entity.SpoutPlayer;

/**
 * A single connection to the server, which may or may not be associated with a
 * player.
 */
public abstract class SpoutSession<T extends SpoutEngine> implements Session {
	/**
	 * The number of ticks which are elapsed before a client is disconnected due
	 * to a timeout.
	 */
	@SuppressWarnings("unused")
	private static final int TIMEOUT_TICKS = 20 * 60;
	/**
	 * The server this session belongs to.
	 */
	private final T engine;
	/**
	 * The Random for this session
	 */
	protected final Random random = new Random();
	/**
	 * The channel associated with this session.
	 */
	protected final Channel channel;
	/**
	 * A queue of incoming and unprocessed messages from a client
	 */
	private final Queue<Message> fromDownMessageQueue = new ArrayDeque<Message>();
	/**
	 * A queue of incoming and unprocessed messages from a server
	 */
	private final Queue<Message> fromUpMessageQueue = new ArrayDeque<Message>();
	/**
	 * A queue of outgoing messages that will be sent after the client finishes identification
	 */
	protected final Queue<Message> sendQueue = new ConcurrentLinkedQueue<Message>();
	/**
	 * The current state.
	 */
	private State state = State.EXCHANGE_HANDSHAKE;
	/**
	 * The player associated with this session (if there is one).
	 */
	protected final AtomicReference<SpoutPlayer> player = new AtomicReference<SpoutPlayer>();
	/**
	 * The random long used for client-server handshake
	 */
	private final String sessionId = Long.toString(random.nextLong(), 16).trim();
	/**
	 * The protocol for this session
	 */
	private final AtomicReference<Protocol> protocol;

	/**
	 * Stores if this is Connected
	 * TODO: Probably add to SpoutAPI
	 */
	protected boolean isConnected = false;

	/**
	 * A network synchronizer that doesn't do anything, used until a real synchronizer is set.
	 */
	private final NetworkSynchronizer nullSynchronizer = new NullNetworkSynchronizer(this);

	/**
	 * The NetworkSynchronizer being used for this session
	 */
	private final AtomicReference<NetworkSynchronizer> synchronizer = new AtomicReference<NetworkSynchronizer>(nullSynchronizer);

	/**
	 * Data map and Datatable associated with it
	 */
	private final DatatableMap datatableMap;
	private final DataMap dataMap;

	/**
	 * Creates a new session.
	 * @param engine  The server this session belongs to.
	 * @param channel The channel associated with this session.
	 */
	public SpoutSession(T engine, Channel channel, Protocol bootstrapProtocol) {
		this.engine = engine;
		this.channel = channel;
		protocol = new AtomicReference<Protocol>(bootstrapProtocol);
		isConnected = true;
		this.datatableMap = new GenericDatatableMap();
		this.dataMap = new DataMap(this.datatableMap);
	}

	/**
	 * Gets the state of this session.
	 * @return The session's state.
	 */
	@Override
	public State getState() {
		return state;
	}

	/**
	 * Sets the state of this session.
	 * @param state The new state.
	 */
	@Override
	public void setState(State state) {
		this.state = state;
	}

	@Override
	public boolean hasPlayer() {
		return getPlayer() != null;
	}

	/**
	 * Gets the player associated with this session.
	 * @return The player, or {@code null} if no player is associated with it.
	 */
	@Override
	public SpoutPlayer getPlayer() {
		return player.get();
	}

	/**
	 * Sets the player associated with this session.
	 * @param player The new player.
	 * @throws IllegalStateException if there is already a player associated
	 *                               with this session.
	 */
	public void setPlayer(SpoutPlayer player) {
		if (!this.player.compareAndSet(null, player)) {
			throw new IllegalStateException();
		}
	}

	@SuppressWarnings("unchecked")
	public void pulse() {
		Message message;

		if (state == State.GAME) {
			while ((message = sendQueue.poll()) != null) {
				send(false, true, message);
			}
		}

		while ((message = fromDownMessageQueue.poll()) != null) {
			MessageHandler<Message> handler = (MessageHandler<Message>) protocol.get().getHandlerLookupService().find(message.getClass());
			if (handler != null) {
				try {
					handler.handle(false, this, message);
				} catch (Exception e) {
					Spout.getEngine().getLogger().log(Level.SEVERE, "Message handler for " + message.getClass().getSimpleName() + " threw exception for player " + (getPlayer() != null ? getPlayer().getName() : "null"));
					e.printStackTrace();
					disconnect(false, new Object[] {"Message handler exception for ", message.getClass().getSimpleName()});
				}
			}
		}
		while ((message = fromUpMessageQueue.poll()) != null) {
			MessageHandler<Message> handler = (MessageHandler<Message>) protocol.get().getHandlerLookupService().find(message.getClass());
			if (handler != null) {
				try {
					handler.handle(true, this, message);
				} catch (Exception e) {
					Spout.getEngine().getLogger().log(Level.SEVERE, "Message handler for " + message.getClass().getSimpleName() + " threw exception for player " + (getPlayer() != null ? getPlayer().getName() : "null"));
					e.printStackTrace();
					disconnect(false, new Object[] {"Message handler exception for", message.getClass().getSimpleName()});
				}
			}
		}
	}

	@Override
	public void send(boolean upstream, Message message) {
		send(upstream, false, message);
	}

	@Override
	public void send(boolean upstream, boolean force, Message message) {
		if (message == null) {
			return;
		}
		try {
			if (force || this.state == State.GAME) {
				if (channel.isOpen()) {
					channel.write(message);
				}
			} else {
				sendQueue.add(message);
			}
		} catch (Exception e) {
			disconnect(false, new Object[] {"Socket Error!"});
		}
	}

	@Override
	public void sendAll(boolean upstream, Message... messages) {
		sendAll(upstream, false, messages);
	}

	@Override
	public void sendAll(boolean upstream, boolean force, Message... messages) {
		for (Message msg : messages) {
			send(upstream, force, msg);
		}
	}

	/**
	 * Returns the address of this session.
	 * @return The remote address.
	 */
	@Override
	public InetSocketAddress getAddress() {
		SocketAddress addr = channel.getRemoteAddress();
		if (!(addr instanceof InetSocketAddress)) {
			return null;
		}

		return (InetSocketAddress) addr;
	}

	@Override
	public String toString() {
		return SpoutSession.class.getName() + " [address=" + channel.getRemoteAddress() + "]";
	}

	/**
	 * Adds a message to the unprocessed queue.
	 * @param message The message.
	 */
	@Override
	public void messageReceived(boolean upstream, Message message) {
		if (upstream) {
			fromUpMessageQueue.add(message);
		} else {
			fromDownMessageQueue.add(message);
		}
	}

	@Override
	public String getSessionId() {
		return sessionId;
	}

	/*public BlockPlacementMessage getPreviousPlacement() {
		return previousPlacement;
	}

	public void setPreviousPlacement(BlockPlacementMessage message) {
		previousPlacement = message;
	}*/

	@Override
	public Protocol getProtocol() {
		return this.protocol.get();
	}

	@Override
	public T getEngine() {
		return engine;
	}

	@Override
	public boolean isConnected() {
		return channel.isOpen();
	}

	@Override
	public DefaultedMap<String, Serializable> getDataMap() {
		return dataMap;
	}

	@Override
	public void setNetworkSynchronizer(NetworkSynchronizer synchronizer) {
		if (synchronizer == null && player == null) {
			this.synchronizer.set(nullSynchronizer);
		} else if (!this.synchronizer.compareAndSet(nullSynchronizer, synchronizer)) {
			throw new IllegalArgumentException("Network synchronizer may only be set once for a given player login");
		} else if (synchronizer != null) {
			synchronizer.setProtocol(protocol.get());
			this.synchronizer.set(synchronizer);
		}
	}

	@Override
	public boolean isPrimary(Channel c) {
		return c == this.channel;
	}

	public void bindAuxChannel(Channel c) {
		throw new UnsupportedOperationException("bindAuxChannel() is only supported for proxies");
	}

	public void closeAuxChannel() {
		throw new UnsupportedOperationException("closeAuxChannel() is only supported for proxies");
	}

	@Override
	public NetworkSynchronizer getNetworkSynchronizer() {
		return synchronizer.get();
	}

}
