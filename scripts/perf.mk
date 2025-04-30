LEVEL ?= test
PERF_IMG = $(NPC_HOME)/ready-to-run/microbench-riscv32e-npc-$(LEVEL).bin
PERF_ARG = -t -f

run-micro: $(SIM_TARGET)
	$(call git_commit, "run microbench")
	$(SIM_TARGET) -d $(DIFF_SO) $(PERF_IMG) $(PERF_ARG) 2> $(BUILD_DIR)/stderr.log

perf: $(PERF_VERILOG_SRC)
	$(MAKE) -C $(YOSYS_HOME) sta \
		DESIGN=PerfTop SDC_FILE=$(YOSYS_HOME)/scripts/default.sdc\
		CLK_FREQ_MHZ=3000 CLK_PORT_NAME=clock O=$(BUILD_DIR)\
		RTL_FILES="$(PERF_VERILOG_SRC)"

opt: $(PERF_VERILOG_SRC)
	@yosys ./scripts/perf.ys > $(BUILD_DIR)/yosys.log
	
.PHONY: perf run-micro