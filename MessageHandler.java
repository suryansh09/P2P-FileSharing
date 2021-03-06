import java.io.*;

/**
 * used to handle messages that are received
 * @author Ankit Pankaj and Suryansh
 *
 */
public class MessageHandler implements Runnable
{
	private int connectedToID = -1;
	private boolean isChoked = true;
	private ClientConnection myClient = null;
	private DataInputStream dis = null;
	private Connection myConnection;
	private BitMap myBitMap;
	private volatile boolean requestSenderStarted = false;
	WriteLog w = new WriteLog();
	private int myID;
	private long start_Download;
	private long stop_Download;

	/**
	 * messageHandler called from client using client and connection as parameters
	 * @param aClient
	 * @param myConnection
	 * @throws IOException
	 */
	public MessageHandler(ClientConnection aClient, Connection myConnection) throws IOException
	{
		this.myConnection = myConnection;
		this.myBitMap = myConnection.getBitMap();
		this.myClient = aClient;
		this.dis = new DataInputStream(new PipedInputStream(aClient.getPipedOutputStream()));
		this.myID = myConnection.getMyPeerID();      
	}

	@Override
	public void run()
	{
		try
		{
			this.sendHandshake(myID);
			this.processHandshake(this.receiveHandshake());
			myClient.setSoTimeout();            
			(new Thread(myClient)).start();
			this.processData();
		}
		catch (Exception e)
		{
			e.printStackTrace();
		} 
	}

	private HandshakeMessage receiveHandshake() throws IOException
	{
		myClient.receive(32);
		byte[] handshakeMsg = new byte[32];
		dis.readFully(handshakeMsg);
		return new HandshakeMessage(handshakeMsg);
	}

	private void sendHandshake(int myPeerID) throws IOException, InterruptedException
	{
		Message msg = new HandshakeMessage(myPeerID);
		myClient.send(msg.getFullMessage());

	}

	private void processData() throws IOException, InterruptedException
	{        
		//now always receive bytes and take action
		while(true)
		{
			Message msg = getNextMessage();
			//now interpret the message and take action

			int payloadLength = msg.getMsgLength() - 1;  //removing  the size of message type
			// System.out.println("msg.getMsgTypeValue()" + msg.getMsgTypeValue());
			switch(msg.getMsgTypeValue())
			{
			case 0:
				// System.out.println("choke message received");
				processChokeMessage();
				break;
			case 1:
				// System.out.println("unchoke message received");
				processUnchokeMessage();
				break;
			case 2:
				processInterestedMessage();
				break;
			case 3:
				processNotInterestedMessage();
				break;
			case 4:
				processHaveMessage(payloadLength);
				break;
			case 5:
				processBitfieldMessage(payloadLength);
				break;
			case 6:
				//System.out.println("message read now take action");
				processRequestMessage();
				break;
			case 7:
				processPieceMessage(payloadLength);
				break;
			default:
				System.out.println("Undef error!!");
			}
		}
	}

	/**
	 * receives message and processes it if msg type value is 5
	 * @param msgLength
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void processBitfieldMessage(int msgLength) throws IOException, InterruptedException
	{
		byte[] BitMap = new byte[msgLength];
		dis.readFully(BitMap);
		myBitMap.setPeerBitMap(connectedToID, BitMap);
		if(myBitMap.hasInterestingPiece(connectedToID))
		{
			InterestedMessage i = new InterestedMessage();
			//System.out.println("Interested MEssage type val = " + i.getMsgTypeValue());
			myClient.send(i.getFullMessage());
		}
		else
		{
			myClient.send((new NotInterestedMessage()).getFullMessage());
		}
	}

	/**
	 * process a piece message
	 * checks if choked or unchoked and responds appropriately
	 * @param msgLength
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void processPieceMessage(int msgLength) throws IOException, InterruptedException
	{
		byte[] pieceBuffer = new byte[4];
		dis.readFully(pieceBuffer);
		int pieceIndex = Utilities.getIntFromByte(pieceBuffer, 0);
		byte[] pieceData = new byte[msgLength - 4];  
		dis.readFully(pieceData);
		stop_Download = System.currentTimeMillis();
		myConnection.addOrUpdatedownloadrate_peer(connectedToID, (stop_Download - start_Download));
		myBitMap.reportPieceReceived(pieceIndex, pieceData);
		w.PieceDownload(Integer.toString(myID), Integer.toString(connectedToID), pieceIndex, myBitMap.getDownloadedPieceCount(myID));

		if (myBitMap.getDownloadedPieceCount(myID) == myBitMap.getTotalPieceCount())
		{
			w.DownloadComplete(Integer.toString(myID));
			myConnection.sendGroupMessage(myConnection.getconnectedPeersList(), new NotInterestedMessage().getFullMessage());
		}

		if(!isChoked)
		{
			int desiredPiece = myBitMap.getPeerPieceIndex(connectedToID);
			if(desiredPiece != -1)
			{
				myClient.send((new RequestMessage(desiredPiece)).getFullMessage());
			}
		}

		myConnection.sendGroupMessage(myConnection.getconnectedPeersList(), (new HaveMessage(pieceIndex)).getFullMessage());
		myConnection.sendGroupMessage(myConnection.computeAndGetWastePeersList(), new NotInterestedMessage().getFullMessage());
	}

	/**
	 * request message sent for the required pirece index
	 * @throws IOException
	 * @throws InterruptedException
	 */
	private void processRequestMessage() throws IOException, InterruptedException
	{        
		byte[] indexBuffer = new byte[4];
		dis.readFully(indexBuffer);
		int pieceIndex = Utilities.getIntFromByte(indexBuffer, 0);
		byte[] dataForPiece = myBitMap.getPieceData(pieceIndex);
		myClient.send((new PieceMessage(pieceIndex, dataForPiece)).getFullMessage());
	}

	private void processHaveMessage(int msgLength) throws IOException, InterruptedException
	{
		byte[] payload = new byte[msgLength];
		dis.readFully(payload);
		int pieceIndex = Utilities.getIntFromByte(payload, 0);
		w.Have(Integer.toString(myID), Integer.toString(connectedToID),pieceIndex );

		myBitMap.reportPeerPieceAvailablity(connectedToID, pieceIndex);
		if(!myBitMap.doIHavePiece(pieceIndex))
		{
			myConnection.reportInterestedPeer(connectedToID);
			myClient.send((new InterestedMessage()).getFullMessage());
		}
	}

	private void processNotInterestedMessage()
	{

		w.NotInterested(Integer.toString(myID), Integer.toString(connectedToID));
		myConnection.reportNotInterestedPeer(connectedToID);
	}

	private void processInterestedMessage()
	{
		w.Interested(Integer.toString(myID), Integer.toString(connectedToID));
		myConnection.reportInterestedPeer(connectedToID);
	}

	private void processUnchokeMessage() throws IOException, InterruptedException
	{
		w.Unchoked(Integer.toString(myID), Integer.toString(connectedToID));
		this.isChoked = false;

		if(!requestSenderStarted)
		{
			(new Thread(new RequestMessageProcessor())).start();
			this.requestSenderStarted  = true;
		}
	}

	private void processChokeMessage()
	{
		w.Choked(Integer.toString(myID), Integer.toString(connectedToID));
		this.isChoked = true;
		myConnection.resetdownloadrate_peer(connectedToID);
	}

	private void processHandshake(HandshakeMessage handshakeMsg) throws IOException, InterruptedException
	{
		byte [] msgBytes = handshakeMsg.getFullMessage();
		byte [] msgHeader = new byte[18];
		System.arraycopy(msgBytes, 0, msgHeader, 0, 18);
		this.connectedToID = handshakeMsg.getPeerID();
		this.myConnection.reportNewClientConnection(this.connectedToID, myClient);
		w.ReceivedHandshake(myID, connectedToID);
		if(myBitMap == null){
			System.out.println("My file is NULL"); 
		}
		if(myBitMap.doIHaveAnyPiece())
		{
			myClient.send(new BitfieldMessage(myBitMap.getMyFileBitMap()).getFullMessage());
		}
	}

	private Message getNextMessage() throws IOException, InterruptedException
	{
		byte[] lengthBuffer = new byte[4];
		try{
                dis.readFully(lengthBuffer);
		}
		catch(Exception e){
                // System.out.println("Connection closed");
                }
                int msgLength = Utilities.getIntFromByte(lengthBuffer, 0);
		byte[] msgType = new byte[1];
		
                try{
                dis.readFully(msgType);
		}
                catch(Exception e){}
                Message m = new Message();
		m.setMsgLength(msgLength);
		switch(msgType[0])
		{
		case 0:
			//System.out.println("CHoke Message Received");
			m.setMsgTypeValue(0);
			m.setMsgType("ChokeMessage");
			break;
		case 1:
			//System.out.println("UnCHoke Message Received");
			m.setMsgTypeValue(1);
			m.setMsgType("UnchokedMessage");
			break;
		case 2:
			//System.out.println("Interested Message Received");

			m.setMsgTypeValue(2);
			m.setMsgType("InterestedMessage");
			break;
		case 3:
			//System.out.println("Not Interested Message");
			m.setMsgTypeValue(3);
			m.setMsgType("NotInterestedMessage");
			break;
		case 4:
			//System.out.println("Have Message Received");
			m.setMsgTypeValue(4);
			m.setMsgType("HaveMessage");
			break;
		case 5:

			//System.out.println("Bitfield message received");
			m.setMsgTypeValue(5);
			m.setMsgType("BitfieldMessage");
			break;
		case 6:
			//System.out.println("request Message Received");
			m.setMsgTypeValue(6);
			m.setMsgType("RequestMessage");
			break;
		case 7:
			//System.out.println("Piece Message Received");
			m.setMsgTypeValue(7);
			m.setMsgType("PieceMessage");
			break;
		default:
			System.out.println("Undefined Message!!!");
		}

		return m;
	}

	class RequestMessageProcessor implements Runnable
	{
		private void sendRequestMessage() throws IOException, InterruptedException
		{
			int desiredPiece = myBitMap.getPeerPieceIndex(connectedToID);
			if(desiredPiece != -1)
			{
				myClient.send((new RequestMessage(desiredPiece)).getFullMessage());
			}
		}

		@Override
		public void run()
		{
			while(! myBitMap.canIQuit())
			{
				try
				{
					this.sendRequestMessage();
					Thread.sleep(5);
				} catch (Exception e)
				{
					e.printStackTrace();
				} 
			}
		}
	}
}

