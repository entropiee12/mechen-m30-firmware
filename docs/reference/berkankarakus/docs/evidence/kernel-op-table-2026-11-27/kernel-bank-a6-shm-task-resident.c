// ===== FUN_00009078 @ 00009078

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

int FUN_00009078(undefined1 param_1,int param_2,ushort param_3)

{
  code *pcVar1;
  undefined4 uVar2;
  undefined1 *puVar3;
  int iVar4;
  
  uVar2 = (*_UNK_00009108)();
  puVar3 = (undefined1 *)(*_UNK_0000910c)(0xffffffff);
  if (puVar3 == (undefined1 *)0x0) {
    (*_UNK_00009114)(&UNK_000090e0,0,0);
    (*_UNK_00009118)(uVar2);
    return 0;
  }
  iVar4 = (uint)param_3 + param_2;
  (*_UNK_00009110)(iVar4,param_2,(uint)param_3);
  *puVar3 = param_1;
  puVar3[1] = 0x10;
  pcVar1 = _UNK_00009118;
  *(int *)(puVar3 + 4) = param_2;
  *(ushort *)(puVar3 + 2) = param_3;
  (*pcVar1)(uVar2);
  return iVar4;
}


// ===== FUN_0000911c @ 0000911c

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_0000911c(char param_1)

{
  undefined4 uVar1;
  undefined1 *puVar2;
  
  uVar1 = (*_UNK_00009144)();
  puVar2 = (undefined1 *)(*_UNK_00009148)((int)param_1);
  if (puVar2 != (undefined1 *)0x0) {
    *puVar2 = 0xff;
  }
  (*_UNK_0000914c)(uVar1);
  return 0;
}


// ===== FUN_00009174 @ 00009174

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_00009174(undefined1 param_1,undefined4 param_2,undefined2 param_3)

{
  undefined4 uVar1;
  undefined1 *puVar2;
  undefined4 uVar3;
  
  uVar3 = 0xffffffff;
  uVar1 = (*_UNK_000091dc)();
  puVar2 = (undefined1 *)func_0x0f189150(0xffffffff);
  if (puVar2 == (undefined1 *)0x0) {
    (*_UNK_000091e0)(&UNK_000091b8,0,0);
  }
  else {
    *puVar2 = param_1;
    *(undefined4 *)(puVar2 + 4) = param_2;
    *(undefined2 *)(puVar2 + 2) = param_3;
    uVar3 = 0;
  }
  (*_UNK_000091e4)(uVar1);
  return uVar3;
}


// ===== FUN_000091e8 @ 000091e8

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_000091e8(char param_1)

{
  undefined4 uVar1;
  undefined1 *puVar2;
  
  uVar1 = (*_UNK_00009210)();
  puVar2 = (undefined1 *)func_0x0f189150((int)param_1);
  if (puVar2 != (undefined1 *)0x0) {
    *puVar2 = 0xff;
  }
  (*_UNK_00009214)(uVar1);
  return 0;
}


// ===== FUN_00009218 @ 00009218

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_00009218(char param_1)

{
  undefined4 uVar1;
  int iVar2;
  undefined4 uStack_18;
  
  uStack_18 = 0;
  uVar1 = (*_UNK_00009240)();
  iVar2 = func_0x0f189150((int)param_1);
  if (iVar2 != 0) {
    uStack_18 = *(undefined4 *)(iVar2 + 4);
  }
  (*_UNK_00009244)(uVar1);
  return uStack_18;
}


// ===== FUN_00009944 @ 00009944

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_00009944(undefined4 *param_1,uint param_2,uint param_3)

{
  ushort uVar1;
  code *pcVar2;
  uint uVar3;
  uint *puVar4;
  int iVar5;
  undefined4 uStack_28;
  undefined4 uStack_24;
  undefined4 uStack_20;
  uint uStack_18;
  
  uStack_20 = param_1[2];
  uStack_24 = param_1[1];
  uStack_28 = *param_1;
  param_2 = param_2 & 0xff;
  uStack_18 = param_3 & 0xff;
  uVar3 = func_0x0fc196d0(param_2);
  if (uVar3 < 8) {
    puVar4 = (uint *)(uVar3 * 8 + _UNK_000099a8);
    uVar3 = param_1[2] & _UNK_000099ac;
    *(undefined2 *)(puVar4 + 1) = *(undefined2 *)(param_1 + 3);
    *puVar4 = uVar3;
  }
  uVar1 = *(ushort *)(param_1 + 3);
  iVar5 = param_1[2];
  (*_UNK_000099b0)(iVar5 - (uint)uVar1,0x5a);
  pcVar2 = _UNK_000099b4;
  *(uint *)(param_2 * 4 + _UNK_000099a8 + 0x40) = (iVar5 - (uint)uVar1) + 0xc0 & _UNK_000099ac;
  (*pcVar2)(&uStack_28,param_2,uStack_18);
  return;
}


// ===== FUN_bfc08770 @ bfc08770

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_bfc08770(undefined4 param_1,undefined4 param_2,undefined1 param_3)

{
  byte *pbVar1;
  code *pcVar2;
  
  pcVar2 = _UNK_bfc087a0;
  pbVar1 = _UNK_bfc0879c;
  if ((*_UNK_bfc0879c & 2) == 0) {
    return;
  }
  *_UNK_bfc0879c = *_UNK_bfc0879c | 4;
  (*pcVar2)(param_1,param_2,param_3);
  *pbVar1 = *pbVar1 & 0xfb;
  return;
}


// ===== FUN_bfc087a4 @ bfc087a4

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_bfc087a4(undefined4 param_1,undefined4 param_2,undefined1 param_3)

{
  if (((*_UNK_bfc087c8 & 2) == 0) || ((*_UNK_bfc087c8 & 4) != 0)) {
    return;
  }
  func_0xbfc04a2c(param_1,param_2,param_3);
  return;
}


