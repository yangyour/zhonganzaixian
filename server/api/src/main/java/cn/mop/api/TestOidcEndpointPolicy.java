package cn.mop.api;

import java.net.URI;

/** Explicit project-only identity endpoints; a request Host header never selects an issuer. */
final class TestOidcEndpointPolicy {
    private TestOidcEndpointPolicy() {}

    static void validateUnified(String issuer,String client,String secret){
        try{
            URI uri=URI.create(issuer);
            boolean localTest="http".equals(uri.getScheme())&&("127.0.0.1".equals(uri.getHost())||uri.getPath().equals("/realms/mop-acceptance"));
            if((!"https".equals(uri.getScheme())&&!localTest)||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null||issuer.endsWith("/")||!uri.normalize().equals(uri)||client==null||!client.matches("[A-Za-z0-9._:-]{1,200}")||secret==null||secret.isBlank())throw new IllegalArgumentException();
        }catch(RuntimeException invalid){throw new IllegalStateException("FAIL_CLOSED: explicit unified OIDC issuer, client and secret are required");}
    }

    static void validate(String issuer, String client, String secret, String publicOrigin) {
        boolean valid = false;
        if (issuer != null && client != null && secret != null && !secret.isBlank() && publicOrigin != null) {
            if (publicOrigin.isEmpty()) {
                valid = issuer.matches("http://127\\.0\\.0\\.1:[0-9]+/realms/mop-test") && client.equals("mop-web");
            } else if (validOrigin(publicOrigin)) {
                valid = issuer.equals(publicOrigin + "/realms/mop-acceptance") && client.equals("mop-web-acceptance");
            }
        }
        if (!valid) throw new IllegalStateException("FAIL_CLOSED: configure the pinned local test identity or the explicit project acceptance origin, realm and client");
    }

    static boolean validOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (value.length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null ||
                uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().isEmpty() ||
                uri.getPort() == 0 || uri.getPort() > 65535 ||
                !value.equals(uri.getScheme() + "://" + uri.getRawAuthority())) return false;
            if (uri.getScheme().equals("https")) return true;
            if (!uri.getScheme().equals("http") || !uri.getHost().matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
            for (String octet : uri.getHost().split("\\.")) {
                if (octet.length() > 3 || (octet.length() > 1 && octet.startsWith("0")) || Integer.parseInt(octet) > 255) return false;
            }
            return !uri.getHost().equals("0.0.0.0");
        } catch (RuntimeException invalid) { return false; }
    }
}
