// ===== FUN_68312d00 @ 68312d00

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

int FUN_68312d00(int param_1)

{
  int iVar1;
  undefined4 uStack_c;
  
  (*_UNK_68312e0c)(_UNK_68312e08,0,0x20,1,0);
  (*_UNK_68312e0c)(_UNK_68312e10,0,0x20,1);
  (*_UNK_68312e18)(0x1d,_UNK_68312e14,0,0x14);
  iVar1 = (*_UNK_68312e18)(*(int *)(param_1 + 0x80) * 0x10 + _UNK_68312e1c + -0x10,0,0,3);
  if (iVar1 == -1) {
    iVar1 = -1;
  }
  else {
    if (*(char *)(*_UNK_68312e20 + 2) == '\0') {
      uStack_c = _UNK_68312e24;
    }
    else {
      uStack_c = _UNK_68312e28;
      if (*(int *)(param_1 + 0x90) < 0xbb81) {
        uStack_c = _UNK_68312e2c;
      }
    }
    iVar1 = (*_UNK_68312e18)(uStack_c,1,0,3);
    if (iVar1 != -1) {
      *_UNK_68312e30 = 0x4800;
      *_UNK_68312e34 = _UNK_68312e38 | *_UNK_68312e34;
      *_UNK_68312e3c = *_UNK_68312e3c & 0xfffffffd;
      *_UNK_68312e40 = 0;
      *_UNK_68312e44 = _UNK_68312e48 | *_UNK_68312e44;
      *_UNK_68312e3c = *_UNK_68312e3c | 2;
    }
  }
  if (iVar1 == -1) {
    (*_UNK_68312e18)(0x1d,0,0,0x15);
  }
  return iVar1;
}


