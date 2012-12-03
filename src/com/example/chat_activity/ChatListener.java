package com.example.chat_activity;

import java.io.UnsupportedEncodingException;

import android.os.Handler;
import android.widget.TextView;
import edu.gvsu.cis.masl.channelAPI.ChannelService;

import org.json.JSONException;
import org.json.JSONObject;


public class ChatListener implements ChannelService {
	
	private Handler myHandler = null;
	private Runnable myRunnable = null;
	
	ChatListener( Handler handler, Runnable updateChat ) {
		myHandler = handler;
		myRunnable = updateChat;
	}
	
	public void onOpen() {
		System.out.println("Channel opened!!!");
		
		myHandler.post(myRunnable);
	}

	public void onMessage(String message) {
		String strmessage = "";
		String from = "";
		JSONObject json;
		try {
			json = new JSONObject(message);
			strmessage = json.getString("message");
			from = json.getString("from");	
		} catch (JSONException e) {
			e.printStackTrace();
		}
		
		if( strmessage.length() > 0 ) { 
			myHandler.post(myRunnable);
		}
	}

	public void onClose() {
	}

	public void onError(Integer errorCode, String description) {
	}
}
