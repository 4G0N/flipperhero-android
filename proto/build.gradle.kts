plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Full protobuf-java (not lite): the generated classes are this module's API, and
    // protobuf-java-util brings JsonFormat, used by the raw-RPC JSON<->proto bridge.
    api(libs.protobuf.java)
    api(libs.protobuf.java.util)
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    // Default builtin (full Java) codegen; no "lite" option.
}
