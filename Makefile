BUILD_DIR = $(shell pwd)/build

PRJ = playground

-include scripts/verilog.mk
-include scripts/verilator.mk
-include scripts/perf.mk

IMG ?= 
ARG ?= -f -t
DIFF_SO ?= $(NEMU_HOME)/build/riscv32-nemu-interpreter-so

clean:
	-rm -rf $(BUILD_DIR)

.PHONY: test verilog help reformat checkformat clean

-include ../Makefile
