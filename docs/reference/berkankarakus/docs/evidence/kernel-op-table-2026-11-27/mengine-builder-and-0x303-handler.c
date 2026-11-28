// ===== FUN_601d0d58 @ 601d0d58

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

void FUN_601d0d58(void)

{
  byte bVar1;
  int *piVar2;
  int iVar3;
  byte bStack_20;
  
  iVar3 = _UNK_601d0ee8;
  bVar1 = *(byte *)(_UNK_601d0eec + 0x41);
  *(byte *)(_UNK_601d0ef0 + 0x19) = bVar1;
  FUN_601d0f18();
  if ((*(char *)(_UNK_601d0ef0 + 0x18) != '\0') && (*(char *)(iVar3 + 0xc) != '\x01')) {
    *(undefined1 *)(iVar3 + 7) = 1;
    if (bVar1 == 0) {
      *(undefined1 *)(iVar3 + 0xd) = 0;
    }
    else {
      *(undefined1 *)(iVar3 + 0xd) = 1;
    }
    for (bStack_20 = 0; bStack_20 < 7; bStack_20 = bStack_20 + 1) {
      *(undefined2 *)((uint)bStack_20 * 8 + iVar3 + 0x10) =
           *(undefined2 *)((uint)bStack_20 * 2 + _UNK_601d0ef4);
      *(undefined2 *)((uint)bStack_20 * 8 + iVar3 + 0x12) =
           *(undefined2 *)((uint)bStack_20 * 2 + _UNK_601d0ef8);
      if (bVar1 == 7) {
        *(short *)((uint)bStack_20 * 8 + iVar3 + 0x14) =
             *(char *)(_UNK_601d0efc + (uint)bStack_20) * 10;
      }
      else {
        *(short *)((uint)bStack_20 * 8 + iVar3 + 0x14) =
             *(char *)((uint)bVar1 * 7 + _UNK_601d0f00 + (uint)bStack_20) * 10;
      }
      *(undefined2 *)((uint)bStack_20 * 8 + iVar3 + 0x16) = 1;
    }
    for (; bStack_20 < 0x10; bStack_20 = bStack_20 + 1) {
      *(undefined2 *)((uint)bStack_20 * 8 + iVar3 + 0x10) = 0;
      *(undefined2 *)((uint)bStack_20 * 8 + iVar3 + 0x12) = 0;
      *(undefined2 *)((uint)bStack_20 * 8 + iVar3 + 0x14) = 0;
      *(undefined2 *)((uint)bStack_20 * 8 + iVar3 + 0x16) = 0;
    }
    (*_UNK_601d0f08)(*_UNK_601d0f04,iVar3,0xf0,0);
    piVar2 = _UNK_601d0f04;
    iVar3 = (*_UNK_601d0f0c)(3,0,0,0x49);
    *piVar2 = iVar3;
    if (*_UNK_601d0f04 == 0) {
      (*_UNK_601d0f0c)(&UNK_601d0ecc,0,0,0x4b);
      do {
                    /* WARNING: Do nothing block with infinite loop */
      } while( true );
    }
    (*_UNK_601d0f14)(*_UNK_601d0f10,0x1f,0);
  }
  return;
}


// ===== FUN_601d0f30 @ 601d0f30

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_601d0f30(int param_1)

{
  (*_UNK_601d0f70)(*_UNK_601d0f6c + 0x18,*(undefined4 *)(param_1 + 4),4,0);
  (*_UNK_601d0f74)(1);
  (*_UNK_601d0f78)(param_1,1);
  return 0x8013;
}


