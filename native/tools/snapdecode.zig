//! Host-side check: decode a captured Rex terminal stream's GHOSTSNP snapshot with the pinned
//! Ghostty and print what came out.  zig build snapdecode -- <stream.bin>
const std = @import("std");
const ghostty = @import("ghostty-vt");

pub fn main(init: std.process.Init) !void {
    const alloc = init.gpa;
    const io = init.io;
    var args = try init.minimal.args.iterateAllocator(alloc);
    _ = args.next();
    const path = args.next() orelse return error.MissingPath;
    const bytes = try std.Io.Dir.cwd().readFileAlloc(io, path, alloc, .unlimited);
    var reader: std.Io.Reader = .fixed(bytes);
    var decoded = try ghostty.snapshot.decode(alloc, io, &reader, .{ .max_continuation_bytes = 1 << 20 });
    defer decoded.deinit(alloc);
    const t = &decoded.terminal.?;
    const used = bytes.len - reader.end + reader.seek;
    _ = used;
    std.debug.print("consumed {d} of {d} bytes; grid {d}x{d}; continuation: {s}\n", .{
        reader.seek, bytes.len, t.cols, t.rows, @tagName(decoded.continuation),
    });
    const text = try t.plainString(alloc);
    std.debug.print("--- screen ---\n{s}\n--- end ---\n", .{text});
}
