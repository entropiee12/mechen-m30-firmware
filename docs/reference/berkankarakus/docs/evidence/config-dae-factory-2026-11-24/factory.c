// ===== FUN_40dd4d60 @ 40dd4d60

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_40dd4d60(int param_1)

{
  undefined1 uVar1;
  byte bStack_38;
  undefined1 auStack_30 [4];
  undefined2 uStack_2c;
  undefined2 uStack_2a;
  undefined2 uStack_28;
  undefined1 auStack_20 [4];
  undefined1 auStack_1c [16];
  
  uVar1 = (*_UNK_40dd4e00)(0x82);
  *(undefined1 *)(param_1 + 3) = uVar1;
  uVar1 = (*_UNK_40dd4e00)(0x85);
  *(undefined1 *)(param_1 + 7) = uVar1;
  for (bStack_38 = 0; bStack_38 < 0x10; bStack_38 = bStack_38 + 1) {
    (*_UNK_40dd4e04)(bStack_38 + 0x86,auStack_20,0xc);
    (*_UNK_40dd4e08)((uint)bStack_38 * 8 + param_1 + 0x10,auStack_1c,8,0);
  }
  uVar1 = (*_UNK_40dd4e00)(0x98);
  *(undefined1 *)(param_1 + 0xc) = uVar1;
  uVar1 = (*_UNK_40dd4e00)(0xa5);
  *(undefined1 *)(param_1 + 0xd) = uVar1;
  (*_UNK_40dd4e04)(0xa6,auStack_30,0xc);
  *(undefined2 *)(param_1 + 0xba) = uStack_2c;
  *(undefined2 *)(param_1 + 0xbc) = uStack_2a;
  *(undefined2 *)(param_1 + 0xbe) = uStack_28;
  return;
}


// ===== FUN_40dd4e0c @ 40dd4e0c

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_40dd4e0c(int param_1,char param_2)

{
  undefined2 uVar1;
  short sStack_28;
  byte bStack_26;
  undefined1 auStack_20 [4];
  undefined2 uStack_1c;
  undefined2 uStack_1a;
  undefined2 uStack_18;
  undefined2 uStack_16;
  
  if (*(char *)(param_1 + 0xc) != '\0') {
    if (param_2 == '\x01') {
      sStack_28 = 0x9f;
      uVar1 = (*_UNK_40dd4f48)(0xa2);
      *(undefined2 *)(param_1 + 0xe2) = uVar1;
    }
    else {
      sStack_28 = 0x99;
      uVar1 = (*_UNK_40dd4f48)(0x9c);
      *(undefined2 *)(param_1 + 0xe2) = uVar1;
    }
    *(undefined1 *)(param_1 + 0xd) = 1;
    for (bStack_26 = 0; bStack_26 < 3; bStack_26 = bStack_26 + 1) {
      (*_UNK_40dd4f4c)((ushort)bStack_26 + sStack_28,auStack_20,0xc);
      *(undefined2 *)((uint)bStack_26 * 8 + param_1 + 0xa2) = uStack_1c;
      *(undefined2 *)((uint)bStack_26 * 8 + param_1 + 0xa4) = uStack_1a;
      *(undefined2 *)((uint)bStack_26 * 8 + param_1 + 0xa6) = uStack_18;
      *(undefined2 *)((uint)bStack_26 * 8 + param_1 + 0xa8) = 0;
      if ((*(char *)(param_1 + 0xd) == '\x01') &&
         (-(int)*(short *)(param_1 + 0xe2) < (int)*(short *)((uint)bStack_26 * 8 + param_1 + 0xa2)))
      {
        *(short *)((uint)bStack_26 * 8 + param_1 + 0xa2) = -*(short *)(param_1 + 0xe2);
      }
      *(undefined2 *)((uint)bStack_26 * 2 + param_1 + 0xe4) =
           *(undefined2 *)((uint)bStack_26 * 8 + param_1 + 0xa2);
      if (*(short *)(param_1 + 0xea) < *(short *)((uint)bStack_26 * 2 + param_1 + 0xe4)) {
        *(undefined2 *)(param_1 + 0xea) = *(undefined2 *)((uint)bStack_26 * 2 + param_1 + 0xe4);
      }
      if (bStack_26 < 2) {
        *(undefined2 *)((uint)bStack_26 * 2 + param_1 + 0x9e) = uStack_16;
      }
    }
  }
  return;
}


