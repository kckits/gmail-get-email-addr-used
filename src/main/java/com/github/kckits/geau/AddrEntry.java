package com.github.kckits.geau;

import java.util.Date;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;

public class AddrEntry {
	private Address addr;
	private Date lastRecv;
	private int count;
	
	private Object recvLock;
	private Object countLock;
	
	public AddrEntry(Address addr) {
		this.addr = addr;
		this.lastRecv = null;
		this.count = 0;
		this.recvLock = new Object();
		this.countLock = new Object();
	}
	
	public AddrEntry processMsg(Message msg) {
		try {
			Date rd = msg.getReceivedDate();
			synchronized(recvLock) {
				if (lastRecv == null || lastRecv.before(rd)) {
					this.lastRecv = rd;
				}
			}
		} catch (MessagingException e) {
			e.printStackTrace();
		}
		synchronized(countLock) {
			this.count += 1;
		}
		
		return this;
	}
	
	public Address getAddr() {
		return this.addr;
	}
	
	public Date getLastRecv() {
		return this.lastRecv;
	}
	
	public int getCount() {
		return this.count;
	}
}
