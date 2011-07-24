package il.technion.ewolf.kbr.openkad;

import il.technion.ewolf.kbr.Key;
import il.technion.ewolf.kbr.KeyFactory;
import il.technion.ewolf.kbr.KeyHolder;
import il.technion.ewolf.kbr.KeybasedRouting;
import il.technion.ewolf.kbr.Node;
import il.technion.ewolf.kbr.NodeConnectionListener;
import il.technion.ewolf.kbr.openkad.KadMessage.RPC;
import il.technion.ewolf.kbr.openkad.net.KadConnection;
import il.technion.ewolf.kbr.openkad.net.KadServer;
import il.technion.ewolf.kbr.openkad.ops.KadOperation;
import il.technion.ewolf.kbr.openkad.ops.KadOperationsExecutor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;


import com.google.inject.Inject;
import com.google.inject.name.Named;

public class KadNet implements KeybasedRouting, KadConnectionListener {

	private final KadNode localNode;
	private final KBuckets kbuckets;
	private final KadServer kadServer;
	private final KadOperationsExecutor opExecutor;
	private final KadListenersServer listenersServer;
	private final KeyFactory keyFactory;
	//private final KadProxyServer kadProxyServer;
	//private final OpenedKadConnections openedKadConnections;
	private final KadRefresher kadRefresher;
	@Inject
	KadNet(
			@Named("kadnet.srv.buffsize") int buffSize,
			@Named("kadnet.executors.incoming") ExecutorService executor,
			@Named("kadnet.localnode") KadNode localNode,
			KeyFactory keyFactory,
			KBuckets kbuckets,
			KadServer kadServer,
			KadOperationsExecutor opExecutor,
			//KadProxyServer kadProxyServer,
			//OpenedKadConnections openedKadConnections,
			KadListenersServer listenersServer,
			KadRefresher kadRefresher) throws IOException {
		
		this.keyFactory = keyFactory;
		
		this.localNode = localNode;
		this.kbuckets = kbuckets;
		
		this.kadServer = kadServer;
		this.kadServer.setKadConnectionListener(this);
		
		//this.kadProxyServer = kadProxyServer;
		//this.kadProxyServer.setKadConnectionListener(this);
		//this.openedKadConnections = openedKadConnections;
		
		this.opExecutor = opExecutor;
		this.listenersServer = listenersServer;
		this.kadRefresher = kadRefresher;
		
	}
	
	@Override
	public void shutdown() {
		kadRefresher.shutdown();
		kadServer.shutdown();
	}
	
	@Override
	public void create() throws IOException {
		for (KadEndpoint endpoint : localNode.getEndpoints()) {
		
			kadServer.register(endpoint.getKadProtocol(), new InetSocketAddress(endpoint.getPort()));
		
		}
		new Thread(kadServer).start();
	}

	@Override
	public Future<Void> join(URI bootstrap) {
		Future<Void> $ =  opExecutor.submitJoinOperation(bootstrap);
		new Thread(kadRefresher).start();
		return $;
	}

	@Override
	public Future<List<Node>> findNodes(Key key, int n) {
		final KadOperation<List<KadNode>> op = opExecutor.createNodeLookupOperation(key, n);
		return opExecutor.submit(new Callable<List<Node>>() {

			@Override
			public List<Node> call() throws Exception {
				
				List<Node> $ = new ArrayList<Node>();
				for (KadNode n : op.call()) {
					$.add(n.getNode(opExecutor));
				}
				return $;
			}
		});
	}

	@Override
	public Set<Node> getNeighbors() {
		Set<Node> $ = new HashSet<Node>();
		for (KadNode n : kbuckets.getAllNodes()) {
			$.add(n.getNode(opExecutor));
		}
		return $;
	}

	@Override
	public void register(String pattern, NodeConnectionListener listener) {
		listenersServer.register(pattern, listener);
	}

	@Override
	public void onIncomingConnection(KadConnection conn) throws IOException {
		boolean keepalive = false;
		//kadProxyServer.receivedIncomingConnection();
		
		try {
			KadMessage msg = conn.recvMessage();
			KadMessageBuilder builder = new KadMessageBuilder();
			onIncomingMessage(msg, builder);
			/*
			if (openedKadConnections.keepAlive(conn, msg)) {
				keepalive = true;
				System.err.println(localNode+": keeping connection alive with "+msg.getLastHop());
				builder.setKeepAlive(true);
			}
			*/
			
			builder.sendTo(conn);
			
		} finally {
			if (!keepalive)
				conn.close();
		}
	}

	private void forward(KadMessage msg, KadMessageBuilder response) throws IOException {
		throw new UnsupportedOperationException();
		/*
		try {
			KadConnection conn = openedKadConnections.get(msg.getDst());
			if (conn == null)
				throw new IOException("not connected to "+msg.getDst());
			
			new KadMessageBuilder(msg)
				.addHop(localNode)
				.setKeepAlive(false)
				.sendTo(conn);
			
			response
				.loadKadMessage(conn.recvMessage())
				.setKeepAlive(false)
				.addHop(localNode);
			
		} catch (Exception e) {
			openedKadConnections.remove(msg.getDst());
			throw new IOException(e);
		}
		*/
	}
	
	@Override
	public void onIncomingMessage(KadMessage msg, KadMessageBuilder response) throws IOException {
		//System.out.println("recved message from: "+addr);
		
		opExecutor.executeInsertNodeOperation(msg.getLastHop());
		
		
		if (msg.getDst() != null && !localNode.getKey().equals(msg.getDst())) {
			forward(msg, response);
			return;
		}
		
		response.addHop(localNode);
		
		switch(msg.getRpc()) {
		case PING:
			response.setRpc(RPC.PING);
			break;
		case FIND_NODE:
			Set<KeyHolder> exclude = new HashSet<KeyHolder>();
			exclude.add(msg.getFirstHop());
			if (msg.getFirstHop().equals(msg.getLastHop())) // direct connection, no need to add myself
				exclude.add(localNode);
			
			response.setRpc(RPC.FIND_NODE)
				.addNodes(kbuckets.getKClosestNodes(msg.getKey(), exclude, msg.getMaxNodeCount()))
				.setKey(msg.getKey());
			break;
		
		case MSG: case CONN:
			listenersServer.incomingListenerMessage(msg, response);
			break;
		}
	}

	@Override
	public KeyFactory getKeyFactory() {
		return keyFactory;
	}

	public String toString() {
		return localNode.toString() +"\n======\n"+kbuckets.toString();
	}

	@Override
	public Node getLocalNode() {
		return localNode.getNode(opExecutor);
	}




}
