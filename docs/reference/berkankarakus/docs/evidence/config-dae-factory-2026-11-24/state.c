// ===== FUN_40dd4c00 @ 40dd4c00

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_40dd4c00(int param_1)

{
  undefined1 uVar1;
  
  uVar1 = (*_UNK_40dd4d58)(200);
  *(undefined1 *)(param_1 + 2) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xc9);
  *(undefined1 *)(param_1 + 3) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xca);
  *(undefined1 *)(param_1 + 4) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xcb);
  *(undefined1 *)(param_1 + 5) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xcc);
  *(undefined1 *)(param_1 + 6) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xcd);
  *(undefined1 *)(param_1 + 0xb) = uVar1;
  *(undefined2 *)(param_1 + 8) = 600;
  uVar1 = (*_UNK_40dd4d58)(0xdb);
  *(undefined1 *)(param_1 + 10) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xce);
  *(undefined1 *)(param_1 + 0xc) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xcf);
  *(undefined1 *)(param_1 + 0xd) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xd0);
  *(undefined1 *)(param_1 + 0xe) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xd1);
  *(undefined1 *)(param_1 + 0xf) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xd2);
  *(undefined1 *)(param_1 + 0x10) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xd3);
  *(undefined1 *)(param_1 + 0x11) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xd8);
  *(undefined1 *)(param_1 + 0x12) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xd9);
  *(undefined1 *)(param_1 + 0x13) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(6);
  *(undefined1 *)(param_1 + 0x14) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xdc);
  *(undefined1 *)(param_1 + 0x15) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xdd);
  *(undefined1 *)(param_1 + 0x16) = uVar1;
  *(char *)(param_1 + 0x21) =
       (char)((ulonglong)*(ushort *)(param_1 + 8) * (ulonglong)_UNK_40dd4d5c >> 0x26);
  *(undefined1 *)(param_1 + 0x22) = 0;
  uVar1 = (*_UNK_40dd4d58)(0xde);
  *(undefined1 *)(param_1 + 0x17) = uVar1;
  *(undefined1 *)(param_1 + 0x1c) = 0x32;
  *(undefined1 *)(param_1 + 0x1d) = 0;
  *(undefined1 *)(param_1 + 0x1e) = 0;
  *(undefined1 *)(param_1 + 0x1f) = 0;
  *(undefined1 *)(param_1 + 0x23) = 0;
  uVar1 = (*_UNK_40dd4d58)(0xd4);
  *(undefined1 *)(param_1 + 0x24) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xd5);
  *(undefined1 *)(param_1 + 0x25) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xd6);
  *(undefined1 *)(param_1 + 0x26) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0x96);
  *(undefined1 *)(param_1 + 0x18) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0x97);
  *(undefined1 *)(param_1 + 0x19) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xdf);
  *(undefined1 *)(param_1 + 0x1a) = uVar1;
  uVar1 = (*_UNK_40dd4d58)(0xe0);
  *(undefined1 *)(param_1 + 0x1b) = uVar1;
  return;
}


// ===== FUN_40dd4f50 @ 40dd4f50

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_40dd4f50(int param_1,char param_2)

{
  undefined1 uVar1;
  
  if (*(char *)(param_1 + 2) != '\0') {
    if (*(char *)(param_1 + 3) == '\x01') {
      *(undefined1 *)(param_1 + 5) = 0;
      *(undefined1 *)(param_1 + 4) = 0;
    }
    else {
      if (param_2 == '\x01') {
        if (*(char *)(param_1 + 0xc) == '\x01') {
          uVar1 = (*_UNK_40dd4fe4)(0x84);
          *(undefined1 *)(param_1 + 5) = uVar1;
          *(undefined1 *)(param_1 + 4) = 0;
        }
        else {
          *(undefined1 *)(param_1 + 5) = 0;
          uVar1 = (*_UNK_40dd4fe4)(0x83);
          *(undefined1 *)(param_1 + 4) = uVar1;
        }
      }
      *(undefined1 *)(param_1 + 6) = 0;
      if (param_2 == '\x01') {
        FUN_40dd4e0c(param_1,0);
      }
    }
  }
  return 1;
}


// ===== FUN_40dd4fe8 @ 40dd4fe8

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_40dd4fe8(int param_1,int param_2)

{
  if (*(char *)(param_1 + 0x18) == '\x01') {
    *(undefined1 *)(param_2 + 2) = 1;
    *(undefined1 *)(param_2 + 3) = 0;
  }
  else {
    *(undefined1 *)(param_2 + 2) = 0;
    *(undefined1 *)(param_2 + 3) = 1;
  }
  FUN_40dd4f50(param_2,1);
  (*_UNK_40dd5030)(param_2);
  return;
}


