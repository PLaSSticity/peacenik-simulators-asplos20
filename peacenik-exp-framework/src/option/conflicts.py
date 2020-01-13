class Conflicts(object):
    """Conflicting sites to be validated by Collision Analysis"""
    
    LI_SITES = []
    
    # Each tuple is a pair of conflicting sites, in the following form:
    # (line0, sleep_time0, line1, sleep_time1)
    
    # vips
    LI_SITES.append((93, 29900, 1004, 36700))
    LI_SITES.append((1004, 29900, 93, 36700))
    LI_SITES.append((94, 29900, 1005, 36700))
    LI_SITES.append((1005, 29900, 94, 36700))
    LI_SITES.append((95, 29900, 546, 36700))
    LI_SITES.append((546, 29900, 95, 36700))
    LI_SITES.append((96, 29900, 1003, 36700))
    LI_SITES.append((1003, 29900, 96, 36700))
    
    # mysqld 0-11
    #LI_SITES.append((3628, 29900, 3628, 36700)) # n (not confirmed)
    #LI_SITES.append((3629, 29900, 3629, 36700)) # n
    #LI_SITES.append((155, 29900, 164, 36700)) # n
    #LI_SITES.append((164, 29900, 155, 36700)) # n   
    LI_SITES.append((4089, 29900, 4089, 36700)) # True
    LI_SITES.append((154, 29900, 154, 36700)) # True
    LI_SITES.append((399, 29900, 915, 36700)) # n
    LI_SITES.append((915, 29900, 399, 36700)) # n
    LI_SITES.append((313, 29900, 2405, 36700)) # n
    LI_SITES.append((2405, 29900, 313, 36700)) # n
    LI_SITES.append((314, 29900, 2406, 36700)) # n
    LI_SITES.append((2406, 29900, 314, 36700)) #7 n
    LI_SITES.append((944, 29900, 944, 36700)) # True
    LI_SITES.append((1101, 29900, 1101, 36700)) #9 True
    LI_SITES.append((1019, 29900, 1019, 36700)) # True
    LI_SITES.append((1513, 29900, 1513, 36700)) #11 n
    LI_SITES.append((162, 29900, 169, 36700)) # n
    LI_SITES.append((169, 29900, 162, 36700)) # n
    LI_SITES.append((170, 29900, 1537, 36700)) # n
    LI_SITES.append((1537, 29900, 170, 36700)) # n
    #LI_SITES.append((409, 29900, 409, 36700)) # false
    #LI_SITES.append((405, 29900, 405, 36700)) # false
    LI_SITES.append((437, 29900, 437, 36700)) #16 n   
    LI_SITES.append((398, 29900, 417, 36700)) # n
    LI_SITES.append((417, 29900, 398, 36700)) # n
    LI_SITES.append((170, 29900, 170, 36700)) # 19 n
    LI_SITES.append((92, 29900, 92, 36700)) # True
    LI_SITES.append((152, 29900, 161, 36700)) # n
    LI_SITES.append((161, 29900, 152, 36700)) # n
    LI_SITES.append((154, 29900, 199, 36700)) # True
    LI_SITES.append((199, 29900, 154, 36700)) # n
    
    LI_SITES.append((99, 29900, 267, 36700)) # 25
    LI_SITES.append((267, 29900, 99, 36700)) # 
    LI_SITES.append((100, 29900, 503, 36700)) # 
    LI_SITES.append((503, 29900, 100, 36700)) # 
    LI_SITES.append((113, 29900, 495, 36700)) # 
    LI_SITES.append((495, 29900, 113, 36700)) # 
    LI_SITES.append((113, 29900, 70, 36700)) # 
    LI_SITES.append((70, 29900, 113, 36700)) #
    LI_SITES.append((113, 29900, 73, 36700)) # 
    LI_SITES.append((73, 29900, 113, 36700)) # 34
    LI_SITES.append((5525, 29900, 5525, 36700)) # 
    LI_SITES.append((102, 29900, 102, 36700)) #
    LI_SITES.append((103, 29900, 103, 36700)) #
    LI_SITES.append((394, 29900, 394, 36700)) # 
    LI_SITES.append((357, 29900, 578, 36700)) #
    LI_SITES.append((578, 29900, 357, 36700)) #
    LI_SITES.append((559, 29900, 560, 36700)) #
    LI_SITES.append((560, 29900, 559, 36700)) #
    LI_SITES.append((404, 29900, 404, 36700)) # 43
    
    #httpd
    LI_SITES.append((76, 29900, 135, 36700)) # 44
    LI_SITES.append((135, 29900, 76, 36700)) # 45
    LI_SITES.append((740, 29900, 766, 36700)) # 46
    LI_SITES.append((766, 29900, 740, 36700)) # 47
    LI_SITES.append((994, 29900, 994, 36700)) # 48
    
    # mysqld lf_hash.c 
    LI_SITES.append((96, 29900, 44, 36700)) # 49 True 
    LI_SITES.append((915, 29900, 915, 36700)) # 50 True 416:915
    LI_SITES.append((169, 29900, 169, 36700)) # 49 True 306:169
    
    # streamcluster 
    LI_SITES.append((1128, 29900, 1128, 36700))
    LI_SITES.append((1129, 29900, 1128, 36700))
    LI_SITES.append((1128, 29900, 1129, 36700))
    LI_SITES.append((727, 29900, 729, 36700))
    LI_SITES.append((729, 29900, 727, 36700))
    LI_SITES.append((1120, 29900, 1147, 36700))
    LI_SITES.append((754, 29900, 751, 36700))
    LI_SITES.append((751, 29900, 754, 36700))
   
