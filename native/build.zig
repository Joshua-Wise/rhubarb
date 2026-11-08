//! Builds librexterm.so: Ghostty's VT core plus a small JNI bridge (adapted from chuchu) that
//! the app feeds with bytes from Rex's terminal data stream and draws from render snapshots.
//!
//!   ANDROID_NDK_HOME=... zig build jni -Doptimize=ReleaseFast
//!
//! copies the library into app/src/main/jniLibs/<abi>/.
const std = @import("std");
const builtin = @import("builtin");
const ndk = @import("src/ndk.zig");
const Translator = @import("translate_c").Translator;

const targets: []const std.Target.Query = &.{
    .{ .cpu_arch = .aarch64, .os_tag = .linux, .abi = .android, .android_api_level = 28 },
    .{ .cpu_arch = .x86_64, .os_tag = .linux, .abi = .android, .android_api_level = 28 },
};

fn ndkPrebuiltTag() []const u8 {
    // The NDK ships only darwin-x86_64 binaries; they run under Rosetta on Apple silicon.
    return switch (builtin.target.os.tag) {
        .macos => "darwin-x86_64",
        .linux => if (builtin.target.cpu.arch == .aarch64) "linux-aarch64" else "linux-x86_64",
        else => @panic("unsupported host for the Android NDK"),
    };
}

pub fn build(b: *std.Build) void {
    const optimize = b.standardOptimizeOption(.{});
    const ndk_home = b.option([]const u8, "android-ndk", "Android NDK root") orelse
        b.graph.environ_map.get("ANDROID_NDK_HOME") orelse
        @panic("set ANDROID_NDK_HOME or -Dandroid-ndk");

    const jni_step = b.step("jni", "Build librexterm.so and copy it into app/src/main/jniLibs");
    for (targets) |q| {
        const target = b.resolveTargetQuery(q);
        const lib = buildLibrary(b, target, optimize, ndk_home);
        const abi = ndk.getOutputDir(target.result) catch unreachable;
        const copy = b.addUpdateSourceFiles();
        copy.addCopyFileToSource(lib.getEmittedBin(), b.fmt("../app/src/main/jniLibs/{s}/librexterm.so", .{abi}));
        jni_step.dependOn(&copy.step);
    }
}

fn buildLibrary(b: *std.Build, target: std.Build.ResolvedTarget, optimize: std.lang.Optimize, ndk_home: []const u8) *std.Build.Step.Compile {
    const triple = ndk.getAndroidTriple(target.result) catch @panic("target must be Android");
    const api: u32 = target.result.os.version_range.linux.android;
    const sysroot = b.pathJoin(&.{ ndk_home, "toolchains", "llvm", "prebuilt", ndkPrebuiltTag(), "sysroot" });
    const libc_file = ndk.createLibC(b, triple, api, sysroot);
    const include_dir = b.pathJoin(&.{ sysroot, "usr", "include" });
    const target_include_dir = b.pathJoin(&.{ include_dir, triple });

    const ghostty = b.dependency("ghostty", .{ .target = target, .optimize = optimize, .simd = false });

    const translator: Translator = .init(b.dependency("translate_c", .{}), .{
        .c_source_file = b.path("src/c.h"),
        .target = target,
        .optimize = optimize,
        .libc_file = libc_file,
    });
    translator.addIncludePath(b.path("src"));
    // NDK r30's headers require a versioned triple; translate-c doesn't pass one, so say it.
    translator.defineCMacro("__ANDROID_API__", b.fmt("{d}", .{api}));
    translator.defineCMacro("__ANDROID_MIN_SDK_VERSION__", b.fmt("{d}", .{api}));

    const mod = b.createModule(.{
        .root_source_file = b.path("src/terminal.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .strip = true,
        .unwind_tables = .none,
        .omit_frame_pointer = true,
    });
    mod.addImport("c", translator.mod);
    mod.addImport("ghostty-vt", ghostty.module("ghostty-vt"));
    mod.addIncludePath(.{ .cwd_relative = include_dir });
    mod.addIncludePath(.{ .cwd_relative = target_include_dir });
    mod.addLibraryPath(.{ .cwd_relative = b.pathJoin(&.{ sysroot, "usr", "lib", triple, b.fmt("{d}", .{api}) }) });
    mod.linkSystemLibrary("log", .{});

    const lib = b.addLibrary(.{ .linkage = .dynamic, .name = "rexterm", .root_module = mod });
    lib.setLibCFile(libc_file);
    lib.link_function_sections = true;
    lib.link_data_sections = true;
    lib.link_gc_sections = true;
    lib.use_llvm = true;
    lib.version_script = b.path("src/version-script.map");
    b.installArtifact(lib);
    return lib;
}
