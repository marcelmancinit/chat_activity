package com.example.chat_activity;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;


public class MainActivity extends Activity {

	Messenger _serviceHandler = null;
	final Messenger _inboxHandler = new Messenger( new IncomingHandler() );
	
	boolean _isBound = false;
	Button _sendButton = null;
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        _sendButton = (Button)findViewById(R.id.send);
        _sendButton.setOnClickListener(onSend);
        _sendButton.setEnabled(false);
        
        TextView tw = (TextView)findViewById(R.id.chat);
        tw.setMovementMethod( new ScrollingMovementMethod() );
    }
    
    @Override
    public void onStart() {
    	super.onStart();
    	TextView tw = (TextView)findViewById(R.id.status);
    	tw.setText( R.string.connecting_status );
    	startService( new Intent(this, ChatConnector.class) );
    }
    
    private View.OnClickListener onSend=new View.OnClickListener() {
        public void onClick(View v) {
        	EditText message_editor = (EditText)findViewById(R.id.message);
        	String text = message_editor.getText().toString();
        	message_editor.getText().clear();
        	sendMessageToService( ChatConnector.message, text );
        }
    };
    
    @Override
    public void onResume() {
    	super.onResume();
    	doBindService();
    }
    
    
    @Override
    public void onPause() {
    	super.onPause();
    	doUnbindService();
    }
    
    @Override
    public void onStop() {
    	super.onStop();
    	stopService( new Intent(this, ChatConnector.class) );
    }
    
    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            	case ChatConnector.server_connected: {
            			_sendButton.setEnabled(true);
            			TextView status = (TextView)findViewById(R.id.status);
            			status.setText("Channel opened");
            		}
            		break;
            	case ChatConnector.new_message: {
            			addMessaToDisplay( (String )msg.obj );
            		}
            		break;
            	default:
            		System.out.println( "Message : " + msg.obj );
            		break;
            }
        }
    }
    
    public void addMessaToDisplay( String message ) {
		TextView tw = (TextView)findViewById(R.id.chat);
		tw.append( message + System.getProperty ("line.separator") );
    }
    
    private ServiceConnection _servConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
        	_serviceHandler = new Messenger(service);
        	_isBound = true;
        	
        	sendMessageToService( ChatConnector.add_client, "empty" );
        	sendMessageToService( ChatConnector.connect, "empty" );
        }

        public void onServiceDisconnected(ComponentName className) {
        	System.out.println( "Service disconnected" );
        	sendMessageToService( ChatConnector.disconnect, "empty" );
        }
    };
    
    private void sendMessageToService( int type, String message ) {
	    if ( ( _serviceHandler != null) && _isBound ) {
	        try {
	            Message msg = Message.obtain( null, type, message );
	            msg.replyTo = _inboxHandler;
	            _serviceHandler.send(msg);
	        } catch (RemoteException e) {
	        	System.out.println( "Can't send message to server because : " + e.toString() );
	        }
	    }
    }
    
    void doBindService() {
        bindService(new Intent(this, ChatConnector.class), _servConnection, Context.BIND_AUTO_CREATE);
    }
    
    void doUnbindService() {
    	if( _isBound )
    		unbindService( _servConnection );
    }
}
