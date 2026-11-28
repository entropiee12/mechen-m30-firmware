// ===== FUN_60b10f7c @ 60b10f7c

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_60b10f7c(int param_1)

{
  undefined1 uVar1;
  byte bStack_38;
  undefined1 auStack_30 [4];
  undefined2 uStack_2c;
  undefined2 uStack_2a;
  undefined2 uStack_28;
  undefined1 auStack_20 [4];
  undefined1 auStack_1c [16];
  
  uVar1 = (*_UNK_60b1101c)(0x82);
  *(undefined1 *)(param_1 + 3) = uVar1;
  uVar1 = (*_UNK_60b1101c)(0x85);
  *(undefined1 *)(param_1 + 7) = uVar1;
  for (bStack_38 = 0; bStack_38 < 0x10; bStack_38 = bStack_38 + 1) {
    (*_UNK_60b11020)(bStack_38 + 0x86,auStack_20,0xc);
    (*_UNK_60b11024)((uint)bStack_38 * 8 + param_1 + 0x10,auStack_1c,8,0);
  }
  uVar1 = (*_UNK_60b1101c)(0x98);
  *(undefined1 *)(param_1 + 0xc) = uVar1;
  uVar1 = (*_UNK_60b1101c)(0xa5);
  *(undefined1 *)(param_1 + 0xd) = uVar1;
  (*_UNK_60b11020)(0xa6,auStack_30,0xc);
  *(undefined2 *)(param_1 + 0xba) = uStack_2c;
  *(undefined2 *)(param_1 + 0xbc) = uStack_2a;
  *(undefined2 *)(param_1 + 0xbe) = uStack_28;
  return;
}


