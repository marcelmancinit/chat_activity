package com.example.chat_activity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.params.ClientPNames;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Service;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import edu.gvsu.cis.masl.channelAPI.ChannelAPI;
import edu.gvsu.cis.masl.channelAPI.ChannelAPI.ChannelException;
import edu.gvsu.cis.masl.channelAPI.ChannelService;


public class ChatConnector extends Service {
	
	public static String server_adreess = "http://channelchatservlet.appspot.com";
	
	private Account _account = null;
	private AccountManager _accountManager = null;
	private String _authToken = null;
	private String _serverToken =  null;
	private ChannelAPI _channelAPI = null;
	private DefaultHttpClient _httpClient = new DefaultHttpClient();
	
	private ArrayList<Messenger> _clients = new ArrayList<Messenger>();	// Client tracking
	
	private final Messenger _inboxHandler = new Messenger( new IncomingHandler() );
	
	static final int add_client = 1;
	static final int message = 2;
	static final int connect = 3;
	static final int disconnect = 4;
	
	static final int server_connected = 5;
	static final int new_message = 6;
	
	public enum connectorState {
		requesting_auth_token(1),
		auth_token_present(2),
        auth_token_invalid(3),
        requesting_cookie(4),
        cookie_present(5),
        requesting_server_token(6),
        server_token_present(7),
        channel_opened(8),
        uninitialized(9);

        private connectorState( int value )
        {
			m_value = (byte)value;
        }

		public byte value()
		{
			return m_value;
		}

		private byte m_value;
	}
	
	volatile connectorState _conState = connectorState.uninitialized;
	
	
	class IncomingHandler extends Handler { // Handler of incoming messages from clients.
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            	case add_client:
            		synchronized (_clients) {
            			if( !_clients.contains( msg.replyTo ) )
            				_clients.add( msg.replyTo );
            		}
            		break;
            	case message:
            		sendMessageToChat( ( String )msg.obj );
            		break;
            	case connect:
           			connectToChat();
            		break;
            	case disconnect:
            		disconnectFromChat();
            		break;
	            default:
	                System.out.println( "Service received message " + msg.obj );
	                break;
            }
        }
    }
	
	private synchronized void sendMessageToUI( int type, String message ) {
		synchronized (_clients) {
			for ( Messenger handler : _clients ) {
				try {
		            handler.send( Message.obtain( null, type, message ) );
				} catch (RemoteException e) {
		            // The client is dead. Remove it from the list; 
					_clients.remove(handler);
		        }
		    }
		}
	}
	
	@Override
    public void onCreate() {
		super.onCreate();
		
		_accountManager = AccountManager.get(getApplicationContext());
        Account[] accounts = _accountManager.getAccountsByType("com.google");
        _account = accounts[0];
        
        requestAuthToken();
    }

	@Override
	public IBinder onBind(Intent arg0) {
        return _inboxHandler.getBinder();
	}	
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		disconnectFromChat();
	}
	
	public void connectToChat() {
		// Get the token maybe do it in separate thread not async task?
		if( _conState == connectorState.auth_token_present ) {
			requestCookie();
		}
	}
	
	public void sendMessageToChat( String message ) {
		if( _conState == connectorState.channel_opened ) {
			// Send message to chat
			new SendTask().execute(message);
		}
	}
		
	public void disconnectFromChat() {
		// Make disconnect request
		if( _conState == connectorState.channel_opened ) {
			try {
				_channelAPI.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		if( _conState.value() > connectorState.server_token_present.value() ) {
			List<NameValuePair> params = new ArrayList<NameValuePair>(1);
			params.add(new BasicNameValuePair("type", "leave"));
		
			makePostRequest( server_adreess + "/chat", params);
		}
		
		_conState = connectorState.auth_token_present;
		
		System.out.println("Channel disconnected");
	}
	
	private void requestAuthToken() {
		_conState = connectorState.requesting_auth_token;
		_accountManager.getAuthToken( _account, "ah", false, new GetAuthTokenCallback(), null );
	}
	
	public void requestCookie() {
		_conState = connectorState.requesting_cookie;
		new GetCookieTask().execute( _authToken );
	}
	
	public void requestServerToken() {
		new TokenRequestTask().execute( server_adreess + "/chat" );
	}
	
	private class GetAuthTokenCallback implements AccountManagerCallback<Bundle> {
		public void run(AccountManagerFuture<Bundle> result) {
			Bundle bundle;
			try {
				bundle = result.getResult();
				Intent intent = (Intent)bundle.get(AccountManager.KEY_INTENT);
				if(intent != null) {
					// User input required
					startActivity(intent);
				} else {
					_authToken = bundle.getString(AccountManager.KEY_AUTHTOKEN);
					_conState = connectorState.auth_token_present;
				}
			} catch (OperationCanceledException e) {
				e.printStackTrace();
			} catch (AuthenticatorException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	};
	
	private class GetCookieTask extends AsyncTask<String, Void, Boolean> {
		protected Boolean doInBackground(String... tokens) {
			
			try {
				// Don't follow redirects
				_httpClient.getParams().setBooleanParameter(ClientPNames.HANDLE_REDIRECTS, false);

				String addr = 
					server_adreess + "/_ah/login?continue=http://localhost/&auth=" + tokens[0];
				HttpGet http_get = new HttpGet(addr);
				HttpResponse response = _httpClient.execute(http_get);
				if(response.getStatusLine().getStatusCode() != 302) {
					// Response should be a redirect
					response.getEntity().consumeContent();
					return false;
				}
				
				for(Cookie cookie : _httpClient.getCookieStore().getCookies()) {
					if(cookie.getName().equals("ACSID")) {
						response.getEntity().consumeContent();
						System.out.println( "Cookie received" );
						return true;
					}
				}				
			} 			
			catch (ClientProtocolException e) {
				e.printStackTrace();
			} 
			catch (IOException e) {
				e.printStackTrace();
			}
			finally {
				_httpClient.getParams().setBooleanParameter(ClientPNames.HANDLE_REDIRECTS, true);
			}
			
			return false;
		}
		
		protected void onPostExecute(Boolean result) {
			if( result ) {
				_conState = connectorState.cookie_present;
				requestServerToken();
			}
			else {
				_conState = connectorState.auth_token_invalid;
				_accountManager.invalidateAuthToken( "com.google", _authToken );
				requestCookie();
			}
		}
	}
	
	private class TokenRequestTask extends AsyncTask<String, Void, HttpResponse> {
		@Override
		protected HttpResponse doInBackground(String... urls) {
			List<NameValuePair> params = new ArrayList<NameValuePair>(1);
			params.add(new BasicNameValuePair("type", "get_token"));
				
			return makePostRequest( server_adreess + "/chat", params);
		}
		
		protected void onPostExecute(HttpResponse result) {
			try {
				if( result == null )
					return;
				BufferedReader reader = new BufferedReader(new InputStreamReader(result.getEntity().getContent()));
				String response = reader.readLine();
				result.getEntity().consumeContent();
				reader.close();
				
				JSONObject obj = new JSONObject( response ); 
				_serverToken = obj.getString("token");
				_conState = connectorState.server_token_present;
				
				createChannel();
				
				String messages = obj.getString("messages");
				
				JSONArray arr = new JSONArray(messages);
				
				for( int i = 0; i < arr.length(); i++ ) {
					JSONObject next = arr.getJSONObject(i);
					String next_mess = next.getString("user") + " : " + next.getString("text");
					sendMessageToUI( new_message, next_mess );
				}
			} catch (IllegalStateException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
	}
	
	private class SendTask extends AsyncTask<String, Void, HttpResponse> {
		@Override
		protected HttpResponse doInBackground(String... args) {
			List<NameValuePair> params = new ArrayList<NameValuePair>(2);
			params.add(new BasicNameValuePair("type", "message"));
			params.add(new BasicNameValuePair("text", args[0]));
			
			return makePostRequest( server_adreess + "/chat", params);
		}
	}
	
	public void createChannel() {
		try {
			_channelAPI = new ChannelAPI( server_adreess, _serverToken, new ServerListener() );
			_channelAPI.open();
		}
		catch (IOException e) {
			e.printStackTrace();
		}
		catch (ChannelException e) {
			e.printStackTrace();
		}
	}
	
	public HttpResponse makePostRequest( String adress, List<NameValuePair> params ) {
		try {
			HttpPost http_post = new HttpPost(adress);
			http_post.setEntity(new UrlEncodedFormEntity(params, HTTP.UTF_8));
			return _httpClient.execute(http_post);
		} catch (ClientProtocolException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	public class ServerListener implements ChannelService {

		public void onOpen() {
			System.out.println( "Channel opened" );
			_conState = connectorState.channel_opened;
			sendMessageToUI( server_connected, "" );
		}

		public void onMessage(String message) {
			String mess = "";
			try {
				JSONObject obj = new JSONObject(message);
				mess = obj.getString("user") + " : " + obj.getString("text");
			} catch (JSONException e) {
				e.printStackTrace();
			}			
			
			if( mess.length() > 0 )
				sendMessageToUI( new_message, mess );
		}

		public void onClose() {
		}

		public void onError(Integer errorCode, String description) {
		}		
	}
}
