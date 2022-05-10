package com.github.kckits.geau;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.mail.imap.IMAPFolder;

import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.InternetAddress;

public class MessageProcessor implements Runnable {
	
	private Geau geau;
	private long uidValidity;
	private long[] uids;
	private String searchD;
	
	private static final Logger logger = LoggerFactory.getLogger(MessageProcessor.class);

	public MessageProcessor (Geau geau, long uidValidity, long[] uids, String searchD) {
		this.geau = geau;
		this.uidValidity = uidValidity;
		this.uids = uids;
		this.searchD = searchD;
	}
	
	@Override
	public void run() {
		Folder f = null;
		try {
			f = UIDFolderFactory.getFolder();
			UIDFolder uf = (UIDFolder) f;
			
			if (((IMAPFolder)f).getUIDValidity() == uidValidity) {			
				Message[] msgs = uf.getMessagesByUID(uids);
				
				for (Message msg : msgs) {
					do {
						try {
							for (Address addr : msg.getAllRecipients()) {
								if (addr instanceof InternetAddress) {
									if (((InternetAddress) addr).getAddress().endsWith("@" + searchD)) {
										geau.addAddr(addr, msg);
									}
								}
							}
							break; // break for alive connection
						} catch (FolderClosedException e) {
							if (!UIDFolderFactory.reconnectFolder((Folder)uf)) {
								break;
							}
						}
					} while (true);
				}
			} else {
				logger.error("UID validity expired.");
			}
			f.close();	
		} catch (MessagingException e) {
			e.printStackTrace();
		}
	}
		
}
