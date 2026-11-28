// ===== FUN_40054c54 @ 40054c54

/* WARNING: Removing unreachable block (ram,0x40054d02) */
/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_40054c54(ushort param_1)

{
  bool bVar1;
  char cVar2;
  int iVar3;
  char cStack_18;
  undefined2 uStack_12;
  char cStack_10;
  byte bStack_f;
  
  bStack_f = (byte)param_1 & 0x1f;
  (*_UNK_40054fb0)(10,0xb,0,5);
  (*_UNK_40054fb4)(1,0,0,0x1d);
  (*_UNK_40054fb8)();
  FUN_40054c00();
  (*_UNK_40054fbc)(&uStack_12,0,2,1);
  (*_UNK_40054fc0)(1);
  cStack_18 = (char)((ushort)((param_1 & 0x40) * 0x100 + 1) >> 8);
  if ((cStack_18 != '\0') && (iVar3 = (*_UNK_40054fb4)(1,0,0,9), iVar3 == -1)) {
    cStack_18 = '\0';
  }
  if (cStack_18 != '\0') {
    if ((bStack_f == 0xe) || (bStack_f == 0xf)) {
      cStack_10 = '\x01';
    }
    else if (cStack_18 == '\0') {
      cStack_10 = '\0';
    }
    else {
      cStack_10 = '\x01';
    }
    (*_UNK_40054fc4)(0,0,0,0x19);
    (*_UNK_40054fc8)(10,1);
    (*_UNK_40054fcc)(0);
    (*_UNK_40054fd4)(_UNK_40054fd0,6);
    (*_UNK_40054fc4)(_UNK_40054fd8,0,0,0);
    cVar2 = (*_UNK_40054fe0)(0,0x15e,_UNK_40054fdc);
    (*_UNK_40054fe4)();
    *_UNK_40054fe8 = 0;
    *_UNK_40054fec = 0;
    for (; cStack_18 != '\0'; cStack_18 = cStack_18 << 1) {
      if (cStack_18 < '\0') {
        _UNK_40054ff0[2] = 0;
        _UNK_40054ff0[1] = 0;
        *_UNK_40054ff0 = 0;
        *(undefined4 *)(_UNK_40054ff0 + 4) =
             *(undefined4 *)((uint)*_UNK_40054fec * 4 + _UNK_40054ff4);
        _UNK_40054ff0[3] = *(undefined1 *)(_UNK_40054ff8 + (uint)*_UNK_40054fec);
        iVar3 = (*_UNK_40054ffc)(_UNK_40054ff0,0);
        *_UNK_40055000 = iVar3;
        *_UNK_40055004 = 0;
        if (*_UNK_40055000 == -1) {
          (*_UNK_4005500c)(*(undefined1 *)(_UNK_40055008 + (*_UNK_40054fec & 3)));
          (*_UNK_40054fb4)(0x23,0,0,0x31);
        }
        else {
          bVar1 = true;
          while (bVar1) {
            (*_UNK_40055010)();
            (*_UNK_40055014)(*(undefined4 *)((uint)*_UNK_40055004 * 4 + _UNK_40054ff4),1);
            FUN_40055068(*_UNK_40055004);
            (*_UNK_40055018)(0xff);
            iVar3 = (*_UNK_4005501c)(*_UNK_40055004);
            if (iVar3 == 0) {
LAB_40054ec8:
              (*_UNK_4005500c)(*(undefined1 *)(_UNK_40055008 + (*_UNK_40054fec & 3)));
              (*_UNK_40054fb4)(0x23,0,0,0x31);
              goto LAB_40054f1e;
            }
            FUN_40055108();
            (*_UNK_40055010)();
            (**(code **)((uint)*_UNK_40055004 * 4 + _UNK_40055020))();
            iVar3 = (*_UNK_40055024)(*_UNK_40055004);
            if (iVar3 == 0) goto LAB_40054ec8;
            if (*_UNK_40055004 == 0) {
              (&uStack_12)[*_UNK_40055004] = *_UNK_40055028;
            }
            (*_UNK_40055018)(0xff);
            *_UNK_40055004 = *_UNK_40055004 + 1;
            bVar1 = false;
          }
          if (cStack_10 == '\x01') {
            (*_UNK_4005502c)();
            (*_UNK_40055030)();
            (*_UNK_40055010)();
            (*_UNK_40055034)();
          }
          FUN_40055050(1);
        }
LAB_40054f1e:
        (*_UNK_40055038)();
      }
      *_UNK_40054fec = *_UNK_40054fec + 1;
    }
    (*_UNK_40055018)(10);
    (*_UNK_4005503c)((int)cVar2);
    (*_UNK_40054fc4)(0,0,0,1);
    (*_UNK_40054fc4)(1,0,0,0x19);
    (*_UNK_40055040)();
  }
  (*_UNK_40054fb0)(10,0,0,5);
  (*_UNK_40054fb4)(_UNK_40055044,_UNK_40055048,0x200,0x12);
  (*_UNK_40054fbc)(_UNK_40055044,&uStack_12,2,0);
  (*_UNK_40054fb4)(_UNK_40055044,_UNK_40055048,0x200,0x13);
  (*_UNK_4005504c)(bStack_f);
  return 1;
}


// ===== FUN_40055194 @ 40055194

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_40055194(void)

{
  int iVar1;
  
  iVar1 = (*_UNK_400551c0)();
  if (iVar1 != 0) {
    (*_UNK_400551c4)();
    (*_UNK_400551c8)(0xff);
    (*_UNK_400551cc)();
    (*_UNK_400551c4)();
    (*_UNK_400551c8)(0xff);
  }
  return 1;
}


// ===== FUN_400551d0 @ 400551d0

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_400551d0(void)

{
  int iVar1;
  
  iVar1 = (*_UNK_400551f4)();
  if (iVar1 != 0) {
    (*_UNK_400551f8)();
    (*_UNK_400551fc)(0xff);
    (*_UNK_40055200)();
  }
  return 1;
}


