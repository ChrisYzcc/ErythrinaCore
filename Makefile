BUILD_DIR = $(shell pwd)/build

PRJ = playground

-include scripts/verilog.mk
-include scripts/verilator.mk
-include scripts/perf.mk

DIFF_SO ?= $(NEMU_HOME)/build/riscv64-nemu-interpreter-so
ARG ?= -w -t -d $(DIFF_SO)
IMG ?= 

clean:
	-rm -rf $(BUILD_DIR)

.PHONY: test verilog help reformat checkformat clean

-include ../Makefile
