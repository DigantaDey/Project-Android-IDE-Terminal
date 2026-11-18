package com.forgeide.core.git;

import java.net.URI;

/** Reject embedded credentials, local file protocols and command-like remotes. */
public final class RemotePolicy {
    private RemotePolicy(){}
    public static URI https(String value){
        try{URI u=URI.create(value);if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getFragment()!=null||u.getQuery()!=null||u.getPort()!=-1&&u.getPort()!=443)throw new IllegalArgumentException();return u;}
        catch(RuntimeException e){throw new IllegalArgumentException("Use an HTTPS repository URL without embedded credentials");}
    }
}
