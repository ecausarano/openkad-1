package il.technion.ewolf.kbr;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;

public class DefaultNodeConnectionListener implements NodeConnectionListener {

	public void onIncomingMessage(String tag, Node from, InputStream in) throws IOException {}
	public void onIncomingConnection(String tag, Node from, Socket sock) throws IOException {}
}
