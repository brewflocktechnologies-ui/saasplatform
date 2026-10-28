package com.byteflock.chatplatform.tenancy;

/** Kind of authenticated principal behind a request. */
public enum PrincipalType {

	/** Website visitor, scoped to one contact of one inbox (session claim {@code typ=contact}). */
	CONTACT,

	/** Dashboard agent of a tenant (session claim {@code typ=agent}). */
	AGENT,

	/** Local-dev operator authenticating with {@code X-Admin-Token}; never a production identity. */
	ADMIN

}
