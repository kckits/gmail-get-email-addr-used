package com.github.kckits.geau;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.search.SearchException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.sun.mail.gimap.GmailRawSearchTerm;
import com.sun.mail.iap.Argument;
import com.sun.mail.iap.ProtocolException;
import com.sun.mail.iap.Response;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.protocol.IMAPProtocol;
import com.sun.mail.imap.protocol.IMAPResponse;

public class Geau {
	@Parameter(names={"--username", "-u"}, required = true)
	private static String username;
	@Parameter(names={"--apppass", "-p"}, required = true, description = "Enter \"App password\"", password = true)
	private static String apppass;
	@Parameter(names={"--domain", "-d"}, required = true)
	private static String searchD;
	@Parameter(names={"--help", "-h"}, help = true)
	private boolean help;
	
	private static final int estMsgPerMin = 80;
	private static final int maxThread = 10;
	private static final int rptInterval = 15*1000; // in ms
	
	private long uidValidity;
	private Map<Address,AddrEntry> addrMap;
	private Map<Thread,Message[]> thrd_msgs;
	
	private static final Logger logger = LoggerFactory.getLogger(Geau.class);
	
	public static void main(String[] args) {
		try {
			Geau geau = new Geau();
			JCommander.newBuilder().addObject(geau).build().parse(args);
			geau.init();
		} catch (ParameterException e) {
			logger.error(e.getLocalizedMessage() + ". Gradle run example: gradlew run --args=\"-u username@gmail.com -p app_password -d gmail.com\"");
		}		
	}
	
	public Geau() {
		this.uidValidity = -1;
		addrMap = new ConcurrentHashMap<Address,AddrEntry>();
		thrd_msgs = new ConcurrentHashMap<Thread,Message[]>();
	}
	
	public final long getValidity() {
		return this.uidValidity;
	}
	
	public void addAddr(Address addr, Message msg) {
		AddrEntry ae = null;
		
		if ((ae = addrMap.get(addr)) == null) {
			ae = new AddrEntry(addr);
			addrMap.put(addr, ae);
		}
		
		ae.processMsg(msg);
	}
	
	public void setMsgsFrThrd(Thread thread, Message[] msgs) {
		thrd_msgs.put(thread, msgs);
	}
	
	private void init() {
		try {
			if (UIDFolderFactory.initFactory(username, apppass)) {
				String strs = "";
				
				logger.info("Searching for emails with " + searchD + " domain...");
				IMAPFolder f = ((IMAPFolder)UIDFolderFactory.getFolder());
				long[] uids = this.doSearch(f, new GmailRawSearchTerm("to:@" + searchD));
				this.uidValidity = f.getUIDValidity();
				
				if (uids.length > 1) strs = "s";
				
				logger.info(uids.length + " email" + strs + " matched.");
				f.close(); // Will start new sessions for workers
				int threadReq = ( uids.length / estMsgPerMin ) + 1;
				threadReq = threadReq > maxThread ? maxThread : threadReq;
				logger.debug("Using " + threadReq + " worker thread(s).");
				
				int threadProcessSize = uids.length / threadReq;
				List<Thread> threads = new LinkedList<Thread>();
				long[][] uidArr = new long[threadReq][];
				for (int i = 0; i < threadReq; i++) {
					int remains = uids.length - ((i+1)*threadProcessSize);
					if (remains > 0 && remains < threadReq) {
						uidArr[i] = Arrays.copyOfRange(uids, i*threadProcessSize, uids.length);
					} else {
						uidArr[i] = Arrays.copyOfRange(uids, i*threadProcessSize, (i+1)*threadProcessSize);
					}
					
					Thread t = new Thread(new MessageProcessor(this, uidValidity, uidArr[i], searchD));
					threads.add(t);
					t.start();
				}
				logger.debug("All worker thread(s) started.");
				logger.info("Processing email" + strs + "...");
				
				long startTime, lastReported; 
				startTime = lastReported = new Date().getTime();
				do {
					// Check threads status
					Iterator<Thread> tt = threads.iterator();
					while(tt.hasNext()) {
						if (!tt.next().isAlive()) {
							tt.remove();
						}
					}
					
					int processedMsg = 0;
					for (AddrEntry ae : addrMap.values()) {
						processedMsg += ae.getCount();
					}
					
					// Report current progress
					long currentTime = (new Date()).getTime();
					if (currentTime > lastReported + rptInterval) {
						logger.info("Processed " + processedMsg + "/" + uids.length + " (" + Math.round(processedMsg*100/uids.length) + "%) "
											+ "ETA : " + (new Date((long)(uids.length/((double)processedMsg/(currentTime-startTime))) + startTime)).toString());
						lastReported = currentTime;
					}
					
					// Wait a while
					try {
						if (!threads.isEmpty()) {
							Thread.sleep(1000);
						} else {break;}
					} catch (InterruptedException e) {}
					
				} while(true);
				logger.info("Process completed.");
				
				if (addrMap.size() > 0) {
					Date td = new Date();
					for (AddrEntry ae : addrMap.values()) {
						logger.info("[REPORT] " + ((InternetAddress) ae.getAddr()).getAddress().toString() + " (" + ae.getCount() + ") : Last received at " + Duration.between(ae.getLastRecv().toInstant(), td.toInstant()).toDays() + " day(s) ago"); 
					}
				}
			} else {
				logger.error("Authentication failed");
			}
		} catch (MessagingException e) {
			logger.error("",e);
		}
	}
	
	@SuppressWarnings("unused")
	private synchronized Message[] search(IMAPFolder folder, GmailRawSearchTerm term)
			throws MessagingException {
		try {
		    Message[] matchMsgs = null;

//		    synchronized(messageCacheLock) {
		    long[] matches = this.doSearch(folder, term);
			if (matches != null)
			    matchMsgs = folder.getMessagesByUID(matches);
//		    }
		    return matchMsgs;

		} catch (SearchException sex) {
		    // too complex for IMAP
//		    if (((IMAPStore)folder.getStore()).throwSearchException())
			throw sex;
//		    return folder.search(term);
		}
	}
	
	private synchronized long[] doSearch(IMAPFolder folder, GmailRawSearchTerm term)
			throws MessagingException {
		
		long[] matches = (long[])folder.doCommand(new IMAPFolder.ProtocolCommand() {
													        public long[] doCommand(IMAPProtocol p) throws ProtocolException {
													            // Issue command
													        	// https://developers.google.com/gmail/imap/imap-extensions
													        	// https://www.rfc-editor.org/rfc/rfc3501#section-9
													        	Argument result = new Argument();
													        	result.writeAtom("X-GM-RAW");
													        	result.writeString(term.getPattern());
													        	result.writeAtom("ALL");
													        	
													        	Response[] r = p.command("UID SEARCH", result);
													        	Response response = r[r.length-1];
													        	long[] matches = null;
												
													            // Grab response
													            if (response.isOK()) { // command succesful 
													        	    List<Long> v = new ArrayList<>();
													        	    long num;
													                for (int i = 0, len = r.length; i < len; i++) {
													                    if (!(r[i] instanceof IMAPResponse))
													                        continue;
												
													                    IMAPResponse ir = (IMAPResponse)r[i];
													                    // There *will* be one SEARCH response.
													                    if (ir.keyEquals("SEARCH")) {
													                        while ((num = ir.readLong()) != -1)
													                        	v.add(Long.valueOf(num));
													                        r[i] = null;
													                    }
													                }
													                
													                // Copy the list into 'matches'
													        	    int vsize = v.size();
													        	    matches = new long[vsize];
													        	    for (int i = 0; i < vsize; i++)
													        	    	matches[i] = v.get(i).longValue();
													            }
												
													            // dispatch remaining untagged responses
													            p.notifyResponseHandlers(r);
													            p.handleResult(response);
													            return matches;
													        }
													 });
		return matches;	
	}
}
