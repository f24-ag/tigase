/*
 * Tigase Jabber/XMPP Server
 * Copyright (C) 2004-2014 "Artur Hefczyc" <artur.hefczyc@tigase.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. Look for COPYING file in the top folder.
 * If not, see http://www.gnu.org/licenses/.
 */
package tigase.component;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.script.Bindings;

import tigase.component.eventbus.DefaultEventBus;
import tigase.component.eventbus.Event;
import tigase.component.eventbus.EventBus;
import tigase.component.eventbus.EventHandler;
import tigase.component.exceptions.ComponentException;
import tigase.component.modules.Module;
import tigase.component.modules.ModuleProvider;
import tigase.component.modules.ModulesManager;
import tigase.component.modules.impl.AdHocCommandModule.ScriptCommandProcessor;
import tigase.disco.XMPPService;
import tigase.server.AbstractMessageReceiver;
import tigase.server.DisableDisco;
import tigase.server.Packet;
import tigase.util.TigaseStringprepException;
import tigase.xml.Element;
import tigase.xmpp.Authorization;
import tigase.xmpp.BareJID;
import tigase.xmpp.JID;
import tigase.xmpp.StanzaType;

/**
 * Base class for implement XMPP Component.
 * 
 * @author bmalkow
 * 
 * @param <CTX>
 *            {@link Context} of component Should be extended.
 */
public abstract class AbstractComponent<CTX extends Context> extends AbstractMessageReceiver implements XMPPService,
		DisableDisco {

	/**
	 * Implemented by handlers of {@link ModuleRegisteredEvent}.
	 */
	public interface ModuleRegisteredHandler extends EventHandler {

		/**
		 * Fired when new module is registered.
		 */
		public static class ModuleRegisteredEvent extends Event<ModuleRegisteredHandler> {

			private String id;

			private Module module;

			public ModuleRegisteredEvent(String id, Module module) {
				this.module = module;
				this.id = id;
			}

			@Override
			protected void dispatch(ModuleRegisteredHandler handler) {
				handler.onModuleRegistered(id, module);
			}

			/**
			 * @return the module
			 */
			public Module getModule() {
				return module;
			}

			/**
			 * @param module
			 *            the module to set
			 */
			public void setModule(Module module) {
				this.module = module;
			}

		}

		/**
		 * Called when {@link ModuleRegisteredEvent} is fired.
		 * 
		 * @param id
		 *            module identifier.
		 * @param module
		 *            module instance.
		 */
		void onModuleRegistered(String id, Module module);
	}

	protected static final String COMPONENT = "component";

	/**
	 * Context of component.
	 */
	protected final CTX context;

	protected final ScriptCommandProcessor defaultScriptCommandProcessor = new ScriptCommandProcessor() {

		@Override
		public List<Element> getScriptItems(String node, JID jid, JID from) {
			return AbstractComponent.this.getScriptItems(node, jid, from);
		}

		@Override
		public boolean processScriptCommand(Packet pc, Queue<Packet> results) {
			return AbstractComponent.this.processScriptCommand(pc, results);
		}
	};

	protected EventBus eventBus = new DefaultEventBus();

	/** Logger */
	protected final Logger log = Logger.getLogger(this.getClass().getName());

	/** Modules manager */
	protected final ModulesManager modulesManager;

	protected PacketWriter writer = new PacketWriter() {
		@Override
		public void write(Collection<Packet> elements) {
			if (elements != null) {
				for (Packet element : elements) {
					if (element != null) {
						write(element);
					}
				}
			}
		}

		@Override
		public void write(Packet packet) {
			if (log.isLoggable(Level.FINER)) {
				log.finer("Sent: " + packet.getElement());
			}
			addOutPacket(packet);
		}

	};

	/**
	 * Constructs ...
	 * 
	 */
	public AbstractComponent() {
		this(null);
	}

	@SuppressWarnings("unchecked")
	public AbstractComponent(Context context) {
		if (context == null) {
			this.context = createContext();
		} else {
			this.context = (CTX) context;
		}

		this.modulesManager = new ModulesManager(this.context);
	}

	/**
	 * Adds {@link ModuleRegisteredEvent} handler.
	 * 
	 * @param handler
	 *            a module registered handler
	 */
	public void addModuleRegisteredHandler(ModuleRegisteredHandler handler) {
		this.context.getEventBus().addHandler(ModuleRegisteredHandler.ModuleRegisteredEvent.class, handler);
	}

	/**
	 * Creates {@link Context} particular for component implementation. Called
	 * once.
	 * 
	 * @return context instance.
	 */
	protected abstract CTX createContext();

	/**
	 * Creates instance of module.
	 * 
	 * @param moduleClass
	 *            class of module
	 * @return instance of module.
	 */
	protected Module createModuleInstance(Class<Module> moduleClass) throws InstantiationException, IllegalAccessException,
			IllegalArgumentException, InvocationTargetException {
		log.finer("Create instance of: " + moduleClass.getName());
		for (Constructor<?> x : moduleClass.getConstructors()) {
			Object[] args = new Object[x.getParameterTypes().length];
			boolean ok = true;

			for (int i = 0; i < x.getParameterTypes().length; i++) {
				final Class<?> type = x.getParameterTypes()[i];

				Object value;
				if (type.isAssignableFrom(Context.class)) {
					value = context;
				} else if (type.isAssignableFrom(ScriptCommandProcessor.class)) {
					value = defaultScriptCommandProcessor;
				} else {
					value = null;
				}

				ok = ok && value != null;

				args[i] = value;
			}

			if (ok) {
				log.finest("Use constructor " + x);
				return (Module) x.newInstance(args);
			}
		}

		return null;
	}

	/**
	 * Returns version of component. Used for Service Discovery purposes.
	 * 
	 * @return version of component.
	 */
	public abstract String getComponentVersion();

	/**
	 * Returns {@link Context} of component.
	 * 
	 * @return
	 */
	protected CTX getContext() {
		return context;
	}

	/**
	 * Returns default map of components. Keys in map are used as component
	 * identifiers.<br/>
	 * 
	 * This map may be modified by <code>init.properties</code>:<br/>
	 * <code>&lt;component_name&gt;/modules/&lt;module_name&gt;[S]=&lt;module_class&gt;</code>
	 * 
	 * 
	 * @return map of default modules.
	 */
	protected abstract Map<String, Class<? extends Module>> getDefaultModulesList();

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Map<String, Object> getDefaults(Map<String, Object> params) {
		final Map<String, Object> props = super.getDefaults(params);

		Map<String, Class<? extends Module>> modules = getDefaultModulesList();
		if (modules != null)
			for (Entry<String, Class<? extends Module>> m : modules.entrySet()) {
				props.put("modules/" + m.getKey(), m.getValue().getName());
			}

		return props;
	}

	/**
	 * Returns {@link EventBus}.
	 * 
	 * @return {@link EventBus}.
	 */
	public EventBus getEventBus() {
		return eventBus;
	}

	/**
	 * Returns {@link ModuleProvider}. It allows to retrieve instance of module
	 * by given ID.
	 * 
	 * @return {@link ModuleProvider}.
	 */
	public ModuleProvider getModuleProvider() {
		return modulesManager;
	}

	/**
	 * Returns {@link PacketWriter}.
	 * 
	 * @return {@link PacketWriter}.
	 */
	public PacketWriter getWriter() {
		return writer;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void initBindings(Bindings binds) {
		super.initBindings(binds); // To change body of generated methods,

		// choose Tools | Templates.
		binds.put(COMPONENT, this);
	}

	/**
	 * Initialising component modules.
	 * 
	 * @param props
	 *            component properties.
	 */
	protected void initModules(Map<String, Object> props) throws InstantiationException, IllegalAccessException,
			IllegalArgumentException, InvocationTargetException {
		for (Entry<String, Object> e : props.entrySet()) {
			try {
				if (e.getKey().startsWith("modules/")) {
					final String id = e.getKey().substring(8);
					@SuppressWarnings("unchecked")
					final Class<Module> moduleClass = (Class<Module>) Class.forName(e.getValue().toString());
					Module module = createModuleInstance(moduleClass);
					registerModule(id, module);
				}
			} catch (ClassNotFoundException ex) {
				log.warning("Cannot find Module class " + e.getValue().toString() + ".");
			}
		}
	}

	/**
	 * Is this component discoverable by disco#items for domain by non admin
	 * users.
	 * 
	 * @return <code>true</code> - if yes
	 */
	public abstract boolean isDiscoNonAdmin();

	/**
	 * Checks if module with given identifier is registered already.
	 * 
	 * @param id
	 *            module identifier.
	 * @return <code>true</code> if module is registered. Otherwise
	 *         <code>false</code>.
	 */
	public boolean isRegistered(final String id) {
		return this.modulesManager.isRegistered(id);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void processPacket(Packet packet) {
		try {
			boolean handled = this.modulesManager.process(packet);

			if (!handled) {
				final String t = packet.getElement().getAttributeStaticStr(Packet.TYPE_ATT);
				final StanzaType type = (t == null) ? null : StanzaType.valueof(t);

				if (type != StanzaType.error) {
					throw new ComponentException(Authorization.FEATURE_NOT_IMPLEMENTED);
				} else {
					if (log.isLoggable(Level.FINER)) {
						log.finer(packet.getElemName() + " stanza with type='error' ignored");
					}
				}
			}
		} catch (TigaseStringprepException e) {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, e.getMessage() + " when processing " + packet.toString());
			}
			sendException(packet, new ComponentException(Authorization.JID_MALFORMED));
		} catch (ComponentException e) {
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, e.getMessageWithPosition() + " when processing " + packet.toString());
			}
			sendException(packet, e);
		} catch (Exception e) {
			if (log.isLoggable(Level.SEVERE)) {
				log.log(Level.SEVERE, e.getMessage() + " when processing " + packet.toString(), e);
			}
			sendException(packet, new ComponentException(Authorization.INTERNAL_SERVER_ERROR));
		}
	}

	/**
	 * Registers module. If there is module registered with given ID, it will be
	 * unregistered.
	 * 
	 * @param id
	 *            identifier of module.
	 * @param module
	 *            module instance.
	 * @return currently registered module instance.
	 */
	public <M extends Module> M registerModule(final String id, final M module) {
		if (this.modulesManager.isRegistered(id)) {
			this.modulesManager.unregister(id);
		}
		M r = this.modulesManager.register(id, module);
		if (r != null) {
			context.getEventBus().fire(new ModuleRegisteredHandler.ModuleRegisteredEvent(id, r), this);
		}
		return r;
	}

	/**
	 * Removes {@link ModuleRegisteredEvent} handler.
	 * 
	 * @param handler
	 *            handler to remove.
	 */
	public void removeModuleRegisteredHandler(ModuleRegisteredHandler handler) {
		this.context.getEventBus().remove(ModuleRegisteredHandler.ModuleRegisteredEvent.class, handler);
	}

	/**
	 * Converts {@link ComponentException} to XMPP error stanza and sends it to
	 * sender of packet.
	 * 
	 * 
	 * @param packet
	 *            packet what caused exception.
	 * @param e
	 *            exception.
	 */
	protected void sendException(final Packet packet, final ComponentException e) {
		try {
			final String t = packet.getElement().getAttributeStaticStr(Packet.TYPE_ATT);

			if ((t != null) && (t == "error")) {
				if (log.isLoggable(Level.FINER)) {
					log.finer(packet.getElemName() + " stanza already with type='error' ignored");
				}

				return;
			}

			Packet result = e.makeElement(packet, true);
			Element el = result.getElement();

			el.setAttribute("from", BareJID.bareJIDInstance(el.getAttributeStaticStr(Packet.FROM_ATT)).toString());
			if (log.isLoggable(Level.FINEST)) {
				log.log(Level.FINEST, "Sending back: " + result.toString());
			}
			context.getWriter().write(result);
		} catch (Exception e1) {
			if (log.isLoggable(Level.WARNING)) {
				log.log(Level.WARNING, "Problem during generate error response", e1);
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void setProperties(Map<String, Object> props) {
		super.setProperties(props);
		try {
			initModules(props);
		} catch (Exception e) {
			log.log(Level.WARNING, "Can't initialize modules!", e);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void updateServiceEntity() {
		super.updateServiceEntity();
		this.updateServiceDiscoveryItem(getName(), null, getDiscoDescription(), !isDiscoNonAdmin());
	}
}
