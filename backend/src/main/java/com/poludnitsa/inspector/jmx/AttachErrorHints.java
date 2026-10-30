package com.poludnitsa.inspector.jmx;

import java.util.Locale;

/** Turns attach / JMX connect failures into a message with a concrete remediation hint. */
public final class AttachErrorHints {

    private AttachErrorHints() {
    }

    public static String describe(Throwable failure) {
        StringBuilder chain = new StringBuilder();
        boolean attachNotSupported = false;
        boolean security = false;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            chain.append(' ').append(t.getMessage());
            attachNotSupported |= t.getClass().getName().equals("com.sun.tools.attach.AttachNotSupportedException");
            security |= t instanceof SecurityException;
            if (t.getCause() == t) {
                break;
            }
        }
        String text = chain.toString().toLowerCase(Locale.ROOT);
        Throwable root = rootCause(failure);
        StringBuilder message = new StringBuilder()
                .append(root.getClass().getSimpleName()).append(": ").append(root.getMessage());

        if (text.contains("well-known file") || text.contains("permission denied") || text.contains("not the owner")
                || text.contains("access is denied") || text.contains("operation not permitted")) {
            message.append(". Hint: run the inspector as the same OS user as the target JVM.");
        } else if (attachNotSupported || text.contains("unable to open socket file") || text.contains("no such process")
                || text.contains("attach listener")) {
            message.append(". Hint: the target did not answer the attach request. Run the inspector as the same OS"
                    + " user, make sure the target is not started with -XX:+DisableAttachMechanism and that both"
                    + " processes see the same java.io.tmpdir (systemd PrivateTmp=true breaks attach).");
        }
        if (text.contains("logmanager") || text.contains("jboss.modules.system.pkgs")) {
            message.append(" Hint (WildFly): the JMX agent needs the log manager on the system class path: add"
                    + " -Djboss.modules.system.pkgs=org.jboss.byteman,org.jboss.logmanager and"
                    + " -Xbootclasspath/a:<jboss-logmanager.jar> to JAVA_OPTS in standalone.conf.");
        }
        if (text.contains("unsupported protocol: remote")) {
            message.append(" Hint: remote+http:// (WildFly management) needs the JBoss client library; use the platform"
                    + " JMX port (-Dcom.sun.management.jmxremote.port) or attach by PID instead.");
        }
        if (security || text.contains("authentication failed") || text.contains("invalid credentials")) {
            message.append(" Hint: check the JMX username and password.");
        }
        if (text.contains("timed out")) {
            message.append(" Hint: the target did not respond in time (long GC pause, overloaded host or firewall).");
        }
        return message.toString();
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root;
    }
}
