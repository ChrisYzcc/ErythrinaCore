"""
    For TopDown Analysis
    Author: yzcc
"""

import re
import os
import time

time_stamp = str(int(time.time()))

NPC_HOME = os.environ.get("NPC_HOME")

pattern = r"topdown_(\w+):\s*(\d+)"
topdown_file = os.path.join(NPC_HOME, "build", "stderr.log")
topdown_res_file = os.path.join(NPC_HOME, "build", f"topdown_{time_stamp}.txt")

topdown_res = {}

bpu_pattern = r"bpu_(\w+)_(\w+):\s*(\d+)"
bpu_data = {}

icahce_pattern = r"icache_(\w+):\s*(\d+)"
icache_data = {}

dcache_pattern = r"dcache_(\w+):\s*(\d+)"
dcache_data = {}

with open(topdown_file, "r") as f:
    lines = f.readlines()
    topdown = {}
    for line in lines:
        match = re.search(pattern, line)
        if match:
            key = match.group(1)
            value = int(match.group(2))
            topdown[key] = value

        # Get ICache data
        match = re.search(icahce_pattern, line)
        if match:
            key = match.group(1)
            value = int(match.group(2))
            icache_data[key] = value

        # Get DCache data
        match = re.search(dcache_pattern, line)
        if match:
            key = match.group(1)
            value = int(match.group(2))
            dcache_data[key] = value

        # Get BPU data
        match = re.search(bpu_pattern, line)
        if match:
            key = match.group(1)
            subkey = match.group(2)
            value = int(match.group(3))
            if (key not in bpu_data):
                bpu_data[key] = {}
            
            if (subkey not in bpu_data[key]):
                bpu_data[key][subkey] = value
            else:
                bpu_data[key][subkey] += value
 
    topdown_res["FrontendBound"] = topdown["FetchBubbles"] / topdown["TotalSlots"]
    topdown_res["BadSpeculation"] = (topdown["SlotsIssued"] - topdown["SlotsRetired"]) / topdown["TotalSlots"]
    topdown_res["Retiring"] = topdown["SlotsRetired"] / topdown["TotalSlots"]
    topdown_res["BackendBound"] = 1 - topdown_res["FrontendBound"] - topdown_res["BadSpeculation"] - topdown_res["Retiring"]

    topdown_res["FrontendBound_Miss"] = topdown["FetchMissBubbles"] / topdown["FetchBubbles"]
    topdown_res["FrontendBound_Unalign"] = topdown["FetchUnalignBubbles"] / topdown["FetchBubbles"]
    topdown_res["FrontendBound_RedirectResteer"] = topdown["RedirectResteerBubbles"] / topdown["FetchBubbles"]

# Proccess Icache Data
icache_data["HitRate"] = (icache_data["hit"] / (icache_data["hit"] + icache_data["miss"]))
icache_data["MissPenalty"] = (icache_data["miss_penalty_tot"] / icache_data["miss"])

# Proccess DCache Data
dcache_data["HitRate"] = (dcache_data["hit"] / (dcache_data["hit"] + dcache_data["miss"]))
dcache_data["MissPenalty"] = (dcache_data["miss_penalty_tot"] / dcache_data["miss"])

# Proccess BPU Data
if (bpu_data["correct"]["exu"] + bpu_data["wrong"]["exu"] == 0):
    bpu_data["CorrectRate"] = 0
else:
    bpu_data["CorrectRate"] = (bpu_data["correct"]["exu"] / (bpu_data["correct"]["exu"] + bpu_data["wrong"]["exu"]))


with open(topdown_res_file, "w") as f:
    for key, value in topdown_res.items():
        f.write(f"{key}: {value:.2%}\n")

    f.write("-"*40 + "\n")
    
    f.write("ICache Data\n")
    f.write(f"HitRate: {icache_data['HitRate']:.2%}\n")
    f.write(f"MissPenalty: {icache_data['MissPenalty']:.2f}\n")

    f.write("-"*40 + "\n")

    f.write("DCache Data\n")
    f.write(f"HitRate: {dcache_data['HitRate']:.2%}\n")
    f.write(f"MissPenalty: {dcache_data['MissPenalty']:.2f}\n")

    f.write("-"*40 + "\n")

    f.write("BPU Data\n")
    f.write(f"CorrectRate: {bpu_data['CorrectRate']:.2%}\n")

    
print(f"Writing topdown result to {topdown_res_file}")