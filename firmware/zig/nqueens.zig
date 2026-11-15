// Counts the solutions of the 9-queens problem with bit masks: recursion,
// unpredictable branches and bit manipulation.
fn solve(n: u32, cols: u32, d1: u32, d2: u32, row: u32) u64 {
    if (row == n) return 1;
    var count: u64 = 0;
    var free = ~(cols | d1 | d2) & ((@as(u32, 1) << @intCast(n)) - 1);
    while (free != 0) {
        const bit = free & (~free +% 1);
        free ^= bit;
        count += solve(n, cols | bit, (d1 | bit) << 1, (d2 | bit) >> 1, row + 1);
    }
    return count;
}

export fn kernel() u64 {
    return solve(9, 0, 0, 0, 0) * 1000 + solve(6, 0, 0, 0, 0);
}
