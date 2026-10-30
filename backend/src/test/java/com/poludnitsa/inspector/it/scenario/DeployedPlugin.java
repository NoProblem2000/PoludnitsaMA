package com.poludnitsa.inspector.it.scenario;

/** Class "deployed" twice by {@link ScenarioTarget}, once per deployment class loader. */
public class DeployedPlugin {

    public static final byte[] STATIC_STATE = new byte[128 * 1024];
}
