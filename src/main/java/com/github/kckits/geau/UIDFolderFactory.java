package com.github.kckits.geau;

import jakarta.mail.Folder;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.mail.gimap.GmailStore;

public class UIDFolderFactory {
	private static final String PROTOCOL = "gimap";
	private static final String IMAPSRV = "imap.gmail.com";
	private static final String SEARCHF = "[Gmail]/All Mail";
	
	private static Session imapses = null;
	private static String c_user = "";
	private static String c_pass = "";
	private static boolean javamail_debug = false;
	
	private static final Logger logger = LoggerFactory.getLogger(UIDFolderFactory.class);

	public static boolean initFactory(String username, String apppass) throws MessagingException {
		boolean rtn = false;
		
		if (imapses == null) {
			Session t_ses = Session.getInstance(System.getProperties(), null);
			t_ses.setDebug(javamail_debug);
			c_user = username; 
			c_pass = apppass;
			
			try {
				GmailStore t_s = (GmailStore) t_ses.getStore(PROTOCOL);
				t_s.connect(IMAPSRV, c_user, c_pass);
				
				t_s.close();
				imapses = t_ses;
				rtn = true;
			} catch (jakarta.mail.AuthenticationFailedException e) {
				logger.debug("Authentication failed : " + e.getLocalizedMessage(),e);
			}
		} else {
			rtn = true;
		}
		
		return rtn;
	}
	
	public static Folder getFolder() throws MessagingException {
		Folder uidf = null;
		
		if (imapses != null) {
			GmailStore gStore = (GmailStore) imapses.getStore(PROTOCOL);
			gStore.connect(IMAPSRV, c_user, c_pass);
			
			if (gStore.isConnected()) {
				Folder gf = gStore.getFolder(SEARCHF);
				gf.open(Folder.READ_ONLY);
				uidf = (Folder) gf;
			}
		}
		
		return uidf;
	}
	
	public static boolean reconnectFolder(Folder f) {
		boolean isOK = false;
		
		if (f != null) {
			Store s = f.getStore();
			
			try {
				if (!s.isConnected()) {
					s.connect();
					f = s.getFolder(SEARCHF);
					f.open(Folder.READ_ONLY);
				}
	
				isOK = true;
			} catch (MessagingException e) {}
		}
		
		return isOK;
	}
}
