#!/bin/sh
# Installs sbt (its launcher from Maven Central) and, unless only "sbt" is
# asked for, Zig 0.14.1 from ziglang.org. Used by CI; locally any sbt and
# zig on PATH will do.
set -e
mkdir -p "$HOME/.local/bin"
curl -sSfL --retry 5 --retry-all-errors -o "$HOME/.local/sbt-launch.jar" https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/1.10.7/sbt-launch-1.10.7.jar
cat > "$HOME/.local/bin/sbt" <<EOF
#!/bin/sh
exec java -Xss8m -Xmx3g -Dsbt.supershell=false -Dsbt.log.noformat=true -jar $HOME/.local/sbt-launch.jar "\$@"
EOF
chmod +x "$HOME/.local/bin/sbt"
if [ "$1" != "sbt" ]; then
  curl -sSfL --retry 5 --retry-all-errors -o /tmp/zig.tar.xz https://ziglang.org/download/0.14.1/zig-x86_64-linux-0.14.1.tar.xz
  mkdir -p "$HOME/.local/zig"
  tar -xJf /tmp/zig.tar.xz -C "$HOME/.local/zig" --strip-components=1
  ln -sf "$HOME/.local/zig/zig" "$HOME/.local/bin/zig"
fi
echo "$HOME/.local/bin" >> "${GITHUB_PATH:-/dev/null}"
