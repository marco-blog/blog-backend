package net.java21.blog.backend.common.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/** 이름 해석(005 research M16). 기본은 {@link InetAddress#getAllByName}, 시험에서는 가짜를 주입한다. */
@FunctionalInterface
public interface HostResolver {

    List<InetAddress> resolve(String host) throws UnknownHostException;

    static HostResolver system() {
        return host -> List.of(InetAddress.getAllByName(host));
    }
}
