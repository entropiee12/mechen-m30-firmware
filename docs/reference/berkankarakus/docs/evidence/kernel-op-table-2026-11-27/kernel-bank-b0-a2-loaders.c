// ===== FUN_00009408 @ 00009408

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_00009408(undefined4 param_1,uint param_2,undefined4 param_3)

{
  code *pcVar1;
  int iVar2;
  undefined4 *puVar3;
  code *pcVar4;
  undefined1 auStack_28 [8];
  uint uStack_20;
  int iStack_1c;
  int iStack_18;
  uint uStack_14;
  
  uStack_20 = param_2 & 0xff;
  iStack_1c = *_UNK_00009540 + 0x200;
  (*_UNK_00009544)(0,0,0);
  uStack_14 = uStack_20 - 6 & 0xff;
  iVar2 = uStack_20 << 2;
  if (1 < uStack_14) {
    iVar2 = uStack_20 * 0xc;
  }
  if (*(int *)(iVar2 + _UNK_00009548) == 0) {
    iStack_18 = (*_UNK_0000954c)(param_1,0,0);
    if (iStack_18 != 0) {
      (*_UNK_00009550)(iStack_18,0,0);
      (*_UNK_00009554)(0,0,0);
      (*_UNK_00009544)(0,0,0);
      (*_UNK_00009558)(iStack_18,iStack_1c,0x4c);
      iVar2 = (*_UNK_00009560)(iStack_1c + 4,_UNK_0000955c,4);
      if (iVar2 == 0) {
        iVar2 = 3;
        puVar3 = (undefined4 *)(iStack_1c + 8);
        do {
          if (puVar3[1] != 0) {
            (*_UNK_00009550)(iStack_18,0,*puVar3);
            (*_UNK_00009558)(iStack_18,puVar3[2],puVar3[1]);
          }
          iVar2 = iVar2 + -1;
          puVar3 = puVar3 + 3;
        } while (-1 < iVar2);
        (*_UNK_00009564)(*(undefined4 *)(iStack_1c + 0x3c),0,*(undefined4 *)(iStack_1c + 0x38));
        pcVar1 = _UNK_00009568;
        if (uStack_14 < 2) {
          iVar2 = uStack_20 << 2;
        }
        else {
          iVar2 = uStack_20 * 0xc;
        }
        *(undefined4 *)(iVar2 + _UNK_00009548) = *(undefined4 *)(iStack_18 + 4);
        (*pcVar1)(iStack_18,0,0);
        pcVar1 = _UNK_00009574;
        *_UNK_0000956c = _UNK_00009570;
        pcVar4 = *(code **)(iStack_1c + 0x40);
        (*pcVar1)(auStack_28,iStack_1c + 0x44,8);
        pcVar1 = _UNK_00009554;
        (*_UNK_00009554)(0,0,0);
        (*_UNK_00009544)(0,0,0);
        iVar2 = (*pcVar4)(param_3,auStack_28);
        if (iVar2 == 0) {
          (*pcVar1)(0,0,0);
          (*pcVar1)(0,0,0);
          return 0;
        }
      }
    }
  }
  (*_UNK_00009554)(0,0,0);
  return 0xffffffff;
}


// ===== FUN_00009578 @ 00009578

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_00009578(undefined4 param_1,uint param_2)

{
  code *pcVar1;
  int iVar2;
  int iVar3;
  int iVar4;
  undefined4 *puVar5;
  
  param_2 = param_2 & 0xff;
  iVar2 = *_UNK_0000966c;
  (*_UNK_00009670)(0,0,0);
  if (*(int *)(param_2 * 0xc + _UNK_00009674 + 8) == 0) {
    iVar3 = (*_UNK_00009678)(param_1,0,0);
    if (iVar3 != 0) {
      (*_UNK_0000967c)(iVar3,0,0);
      (*_UNK_00009680)(0,0,0);
      (*_UNK_00009670)(0,0,0);
      (*_UNK_00009684)(iVar3,iVar2 + 0x200,0x44);
      iVar4 = (*_UNK_0000968c)(iVar2 + 0x204,_UNK_00009688,4);
      if (iVar4 == 0) {
        iVar4 = 3;
        puVar5 = (undefined4 *)(iVar2 + 0x208);
        do {
          if (puVar5[1] != 0) {
            (*_UNK_0000967c)(iVar3,0,*puVar5);
            (*_UNK_00009684)(iVar3,puVar5[2],puVar5[1]);
          }
          iVar4 = iVar4 + -1;
          puVar5 = puVar5 + 3;
        } while (-1 < iVar4);
        (*_UNK_00009690)(*(undefined4 *)(iVar2 + 0x23c),0,*(undefined4 *)(iVar2 + 0x238));
        *(undefined4 *)(param_2 * 0xc + _UNK_00009674 + 8) = *(undefined4 *)(iVar3 + 4);
        *(undefined4 *)(param_2 * 8 + _UNK_00009694 + 4) = *(undefined4 *)(iVar2 + 0x240);
        (*_UNK_00009698)(iVar3,0,0);
        pcVar1 = _UNK_00009680;
        *_UNK_0000969c = 0;
        (*pcVar1)(0,0,0);
        return 0;
      }
    }
  }
  (*_UNK_00009680)(0,0,0);
  return 0xffffffff;
}


// ===== FUN_000096a0 @ 000096a0

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_000096a0(undefined4 param_1,uint param_2)

{
  code *pcVar1;
  undefined4 *puVar2;
  undefined4 *puVar3;
  int iVar4;
  int iVar5;
  int iVar6;
  undefined4 *puVar7;
  
  pcVar1 = _UNK_000097c0;
  iVar6 = *_UNK_000097bc;
  param_2 = param_2 & 0xff;
  (*_UNK_000097c0)(0,0,0);
  if (*(int *)(param_2 * 0xc + _UNK_000097c4 + 4) == 0) {
    iVar4 = (*_UNK_000097c8)(param_1,0,0);
    if (iVar4 != 0) {
      (*_UNK_000097cc)(iVar4,0,0);
      (*_UNK_000097d0)(0,0,0);
      (*pcVar1)(0,0,0);
      (*_UNK_000097d4)(iVar4,iVar6 + 0x200,0x44);
      pcVar1 = _UNK_000097dc;
      iVar5 = (*_UNK_000097dc)(iVar6 + 0x204,_UNK_000097d8,4);
      if (iVar5 == 0) {
        iVar5 = (*pcVar1)(param_1,_UNK_000097e0,7);
        if (iVar5 == 0) {
          *_UNK_000097e4 = 1;
        }
        iVar5 = 3;
        puVar7 = (undefined4 *)(iVar6 + 0x208);
        do {
          if (puVar7[1] != 0) {
            (*_UNK_000097cc)(iVar4,0,*puVar7);
            (*_UNK_000097d4)(iVar4,puVar7[2],puVar7[1]);
          }
          iVar5 = iVar5 + -1;
          puVar7 = puVar7 + 3;
        } while (-1 < iVar5);
        (*_UNK_000097e8)(*(undefined4 *)(iVar6 + 0x23c),0,*(undefined4 *)(iVar6 + 0x238));
        *(undefined4 *)(param_2 * 0xc + _UNK_000097c4 + 4) = *(undefined4 *)(iVar4 + 4);
        *(undefined4 *)(param_2 * 8 + _UNK_000097ec) = *(undefined4 *)(iVar6 + 0x240);
        puVar3 = _UNK_00009800;
        puVar2 = _UNK_000097f8;
        puVar7 = _UNK_000097f4;
        if (param_2 == 0) {
          *_UNK_000097f0 = 0;
          *puVar7 = 0;
          *puVar2 = 0;
        }
        else {
          *_UNK_000097fc = 0;
          *puVar3 = 0;
        }
        (*_UNK_00009804)(iVar4,0,0);
        (*_UNK_000097d0)(0,0,0);
        return 0;
      }
    }
  }
  (*_UNK_000097d0)(0,0,0);
  return 0xffffffff;
}


// ===== FUN_00009808 @ 00009808

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_00009808(undefined4 param_1,uint param_2)

{
  undefined4 *puVar1;
  undefined4 *puVar2;
  undefined4 *puVar3;
  code *pcVar4;
  int iVar5;
  int iVar6;
  int iVar7;
  undefined4 *puVar8;
  int iVar9;
  
  iVar7 = *_UNK_00009900;
  iVar9 = iVar7 + 0x200;
  (*_UNK_00009904)(0,0,0);
  iVar6 = (param_2 & 0xff) * 4;
  if (*(int *)(iVar6 + _UNK_00009908) == 0) {
    iVar5 = (*_UNK_0000990c)(param_1,0,0);
    if (iVar5 != 0) {
      (*_UNK_00009910)(iVar5,0,0);
      (*_UNK_00009914)(0,0,0);
      (*_UNK_00009904)(0,0,0);
      (*_UNK_00009918)(iVar5,iVar9,0x4c);
      iVar9 = (*_UNK_00009920)(iVar9,_UNK_0000991c,4);
      if (iVar9 == 0) {
        puVar8 = (undefined4 *)(iVar7 + 0x208);
        iVar7 = 4;
        do {
          if (puVar8[1] != 0) {
            (*_UNK_00009910)(iVar5,0,*puVar8);
            (*_UNK_00009918)(iVar5,puVar8[2],puVar8[1]);
          }
          iVar7 = iVar7 + -1;
          puVar8 = puVar8 + 3;
        } while (-1 < iVar7);
        *(undefined4 *)(iVar6 + _UNK_00009908) = *(undefined4 *)(iVar5 + 4);
        puVar3 = _UNK_00009934;
        puVar2 = _UNK_00009930;
        puVar1 = _UNK_0000992c;
        puVar8 = _UNK_00009928;
        if ((param_2 & 0xff) == 0) {
          *_UNK_00009924 = 0;
          *puVar8 = 0;
          *puVar1 = 0;
          *puVar2 = 0;
          pcVar4 = _UNK_00009938;
          (*_UNK_00009938)(*puVar3,0xee,0x20);
          (*pcVar4)(*_UNK_0000993c,0xee,0x20);
        }
        (*_UNK_00009940)(iVar5,0,0);
        (*_UNK_00009914)(0,0,0);
        return 0;
      }
    }
  }
  (*_UNK_00009914)(0,0,0);
  return 0xffffffff;
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


// ===== FUN_00009000 @ 00009000

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

int FUN_00009000(char *param_1)

{
  char cVar1;
  bool bVar2;
  code *pcVar3;
  char cVar4;
  int iVar5;
  undefined4 uVar6;
  char *pcVar7;
  uint uVar8;
  int iVar9;
  undefined4 uVar10;
  int iStack_28;
  char *pcStack_24;
  undefined4 uStack_20;
  int iStack_18;
  char *pcStack_14;
  
  iVar9 = *_UNK_00009174;
  iStack_18 = iVar9 + 0x3c0;
  pcStack_14 = (char *)(iVar9 + 0x3e0);
  (*_UNK_00009178)(0,0,0);
  if ((*param_1 == '\0') || (*param_1 == '^')) {
    uVar10 = 1;
  }
  else {
    (*_UNK_0000917c)(iStack_18,0x20,0xb);
    *(undefined1 *)(iVar9 + 0x3cb) = 0;
    uVar8 = 0;
    bVar2 = false;
    cVar1 = *param_1;
    while (cVar1 != '\0') {
      if (cVar1 == '.') {
        bVar2 = true;
        uVar8 = 8;
      }
      else if (((!bVar2) && (uVar8 < 8)) || ((bVar2 && (uVar8 < 0xb)))) {
        cVar1 = *param_1;
        cVar4 = cVar1 + -0x20;
        if (0x19 < (byte)(cVar1 + 0x9fU)) {
          cVar4 = cVar1;
        }
        *(char *)(uVar8 + iStack_18) = cVar4;
        uVar8 = uVar8 + 1 & 0xff;
      }
      param_1 = param_1 + 1;
      cVar1 = *param_1;
    }
    iVar9 = 0x200;
    while( true ) {
      pcStack_24 = pcStack_14;
      uStack_20 = 0xb;
      iStack_28 = iVar9;
      iVar5 = (*(code *)*_UNK_00009180)(&iStack_28);
      if (iVar5 < 0) break;
      if (*pcStack_14 == '\0') {
        uVar10 = 3;
        goto LAB_00009028;
      }
      iVar5 = (*_UNK_00009184)(pcStack_14,iStack_18,0xb);
      if (iVar5 == 0) {
        (*_UNK_0000917c)(pcStack_14,0,0x20);
        pcStack_24 = pcStack_14;
        uStack_20 = 0x20;
        iStack_28 = iVar9;
        iVar9 = (*(code *)*_UNK_00009180)(&iStack_28);
        uVar10 = 4;
        if (iVar9 < 0) goto LAB_00009028;
        uVar8 = 0;
        uVar6 = (*_UNK_00009188)();
        goto LAB_00009106;
      }
      iVar9 = iVar9 + 0x20;
    }
    uVar10 = 2;
  }
  goto LAB_00009028;
  while (uVar8 = uVar8 + 1 & 0xff, uVar8 < 8) {
LAB_00009106:
    pcVar7 = (char *)(uVar8 * 0x10 + _UNK_0000918c);
    if (*pcVar7 == '\0') {
      *pcVar7 = '\x01';
      break;
    }
  }
  uVar10 = 5;
  (*_UNK_00009190)(uVar6);
  if (uVar8 != 8) {
    iVar5 = uVar8 * 0x10 + _UNK_0000918c;
    iVar9 = *(int *)(pcStack_14 + 0x10) * 0x200;
    *(int *)(iVar5 + 4) = iVar9;
    *(int *)(iVar5 + 0xc) = iVar9;
    pcVar3 = _UNK_00009194;
    *(int *)(iVar5 + 8) = iVar9 + *(int *)(pcStack_14 + 0x14) + -1;
    (*pcVar3)(0,0,0);
    return iVar5;
  }
LAB_00009028:
  pcVar3 = _UNK_00009198;
  (*_UNK_00009198)(&UNK_00009154,0,0);
  (*pcVar3)(&UNK_0000916c,uVar10,2);
  (*_UNK_00009194)(0,0,0);
  return 0;
}


