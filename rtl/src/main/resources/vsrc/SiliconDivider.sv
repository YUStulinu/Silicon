// An iterative radix-2 divider for RV64M, written directly in SystemVerilog
// and used from Chisel as a black box.
//
// div/divu/rem/remu and their 32-bit W forms. One quotient bit per cycle:
// a 64-bit operation takes 64 cycles after the start cycle, a W operation 32.
// Division by zero and signed overflow give the results the ISA defines
// (quotient all ones and remainder the dividend; quotient MIN and remainder 0).
module SiliconDivider (
  input  logic        clock,
  input  logic        reset,
  input  logic        start,
  input  logic        kill,       // abandon the operation in progress
  input  logic [63:0] a,
  input  logic [63:0] b,
  input  logic        is_signed,
  input  logic        word,
  input  logic        want_rem,
  output logic        busy,
  output logic        done,       // result valid; held until accept
  input  logic        accept,
  output logic [63:0] result
);
  localparam logic [1:0] IDLE = 2'd0, RUN = 2'd1, DONE = 2'd2;
  logic [1:0] state;

  logic [63:0] dividend, divisor, quot, rem_r;
  logic [6:0]  count;
  logic        neg_q, neg_r, w_r, rem_sel;
  logic [63:0] special;
  logic        use_special;

  // operands, sign handling and the 32-bit forms
  logic [63:0] a_ext, b_ext, a_abs, b_abs;
  always_comb begin
    if (word) begin
      a_ext = is_signed ? {{32{a[31]}}, a[31:0]} : {32'b0, a[31:0]};
      b_ext = is_signed ? {{32{b[31]}}, b[31:0]} : {32'b0, b[31:0]};
    end else begin
      a_ext = a;
      b_ext = b;
    end
    a_abs = (is_signed && a_ext[63]) ? -a_ext : a_ext;
    b_abs = (is_signed && b_ext[63]) ? -b_ext : b_ext;
  end

  // one step of restoring division: shift the next dividend bit in
  logic [64:0] trial;
  logic [63:0] rem_shift;
  always_comb begin
    rem_shift = {rem_r[62:0], dividend[63]};
    trial = {1'b0, rem_shift} - {1'b0, divisor};
  end

  always_ff @(posedge clock) begin
    if (reset || kill) begin
      state <= IDLE;
    end else begin
      case (state)
        IDLE: if (start) begin
          w_r     <= word;
          rem_sel <= want_rem;
          neg_q   <= is_signed && (a_ext[63] ^ b_ext[63]) && b_ext != 0;
          neg_r   <= is_signed && a_ext[63];
          divisor <= b_abs;
          rem_r   <= 0;
          quot    <= 0;
          // a W operation only needs the low 32 dividend bits
          dividend <= word ? {a_abs[31:0], 32'b0} : a_abs;
          count    <= word ? 7'd32 : 7'd64;
          if (b_ext == 0) begin
            use_special <= 1;
            special <= want_rem ? (word ? {{32{a[31]}}, a[31:0]} : a) : 64'hffff_ffff_ffff_ffff;
          end else if (is_signed && !word && a == 64'h8000_0000_0000_0000 && b == 64'hffff_ffff_ffff_ffff) begin
            use_special <= 1;
            special <= want_rem ? 64'b0 : 64'h8000_0000_0000_0000;
          end else if (is_signed && word && a[31:0] == 32'h8000_0000 && b[31:0] == 32'hffff_ffff) begin
            use_special <= 1;
            special <= want_rem ? 64'b0 : 64'hffff_ffff_8000_0000;
          end else begin
            use_special <= 0;
          end
          state <= RUN;
        end
        RUN: begin
          if (use_special) begin
            state <= DONE;
          end else begin
            if (!trial[64]) begin
              rem_r <= trial[63:0];
              quot  <= {quot[62:0], 1'b1};
            end else begin
              rem_r <= rem_shift;
              quot  <= {quot[62:0], 1'b0};
            end
            dividend <= {dividend[62:0], 1'b0};
            count <= count - 1;
            if (count == 1) state <= DONE;
          end
        end
        DONE: if (accept) state <= IDLE;
        default: state <= IDLE;
      endcase
    end
  end

  logic [63:0] q_fix, r_fix, res;
  always_comb begin
    q_fix = neg_q ? -quot : quot;
    r_fix = neg_r ? -rem_r : rem_r;
    res = rem_sel ? r_fix : q_fix;
    if (w_r) res = {{32{res[31]}}, res[31:0]};
    result = use_special ? special : res;
  end

  assign busy = state != IDLE;
  assign done = state == DONE;
endmodule
