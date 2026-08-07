# Protobuf Lite embeds generated field names (for example rows_) in message metadata and
# resolves them reflectively at runtime. R8 may optimize these fields, but it must not
# remove or rename them or MessageSchema will fail while building the first message.
-keepclassmembers,allowoptimization class com.webterm.terminal.protocol.generated.** extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
