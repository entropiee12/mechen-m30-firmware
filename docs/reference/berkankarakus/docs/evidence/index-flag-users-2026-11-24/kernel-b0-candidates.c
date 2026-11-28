// ===== FUN_bfc08e38 @ bfc08e38

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

int FUN_bfc08e38(int param_1,int param_2,char param_3)

{
  byte bVar1;
  undefined2 uVar2;
  byte *pbVar3;
  byte *pbVar4;
  uint *puVar5;
  byte bVar6;
  
  if (param_3 == '\0') {
    (*_UNK_bfc08f5c)(_UNK_bfc08f58,param_1,8);
    puVar5 = _UNK_bfc08f64;
    pbVar4 = _UNK_bfc08f60;
    *_UNK_bfc08f60 = *_UNK_bfc08f58;
    bVar6 = _UNK_bfc08f58[1];
    *puVar5 = *puVar5 & _UNK_bfc08f68 | (bVar6 & 0xf) << 0x15 | (uint)(bVar6 >> 4) << 0x12;
    bVar1 = _UNK_bfc08f58[2];
    *_UNK_bfc08f6c = *_UNK_bfc08f6c & 0xfffffff8 | bVar1 & 0xf;
    pbVar3 = _UNK_bfc08f58;
    pbVar4[1] = pbVar4[1] & 0xc0 | bVar6 & 3 | (byte)((bVar6 >> 4 & 3) << 2) |
                (byte)((bVar1 & 3) << 4);
    uVar2 = *(undefined2 *)(pbVar3 + 6);
    *(undefined2 *)(pbVar4 + 2) = *(undefined2 *)(pbVar3 + 4);
    pbVar3 = _UNK_bfc08f70;
    *(undefined2 *)(pbVar4 + 4) = uVar2;
    if ((bVar1 >> 6 & 1) == 0) {
      *pbVar3 = *pbVar3 & 0xfe;
      bVar6 = _UNK_bfc08f58[2];
    }
    else {
      bVar6 = *pbVar3;
      *pbVar3 = bVar6 | 1;
      *pbVar3 = bVar6 | 1;
      bVar6 = _UNK_bfc08f58[2];
    }
    if ((bVar6 >> 6 & 2) == 0) {
      bVar6 = *pbVar3 & 0xdf;
    }
    else {
      bVar6 = *pbVar3 | 0x20;
      *pbVar3 = bVar6;
    }
    *pbVar3 = bVar6;
    *_UNK_bfc08f74 = *pbVar4 & 0x7f;
    return 0;
  }
  if (param_3 == '\x01') {
    *(int *)(param_1 * 4 + _UNK_bfc08f78) = param_2;
    return 0;
  }
  if (param_3 != '\x02') {
    return 0;
  }
  param_1 = (uint)*_UNK_bfc08f7c * param_1;
  if (param_2 != 0) {
    param_1 = (param_1 * 1000) / param_2;
  }
  return param_1;
}


// ===== FUN_bfc09de8 @ bfc09de8

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_bfc09de8(void)

{
  byte bVar1;
  uint *puVar2;
  uint *puVar3;
  int iVar4;
  uint uVar5;
  
  puVar2 = _UNK_bfc09f14;
  uVar5 = _UNK_bfc09f10;
  bVar1 = *_UNK_bfc09f08;
  if ((bVar1 & 4) != 0) {
    *_UNK_bfc09f0c = *_UNK_bfc09f0c | _UNK_bfc09f10;
    *puVar2 = *puVar2 | uVar5;
  }
  puVar2 = _UNK_bfc09f18;
  *_UNK_bfc09f18 = *_UNK_bfc09f18 & 0xfffffff7 | 8;
  *_UNK_bfc09f1c = *_UNK_bfc09f1c & 0xffffffbf | 0x40;
  *_UNK_bfc09f20 = *_UNK_bfc09f20 & _UNK_bfc09f24 | _UNK_bfc09f28;
  puVar3 = _UNK_bfc09f2c;
  *puVar2 = *puVar2 | 1;
  puVar2 = _UNK_bfc09f30;
  *puVar3 = *puVar3 & 0xfffffff7 | 8;
  *puVar2 = *puVar2 & 0xfffffeff | 0x100;
  *puVar2 = *puVar2 & 0xffffffef;
  *puVar2 = *puVar2 & 0xfffffff0 | 2;
  puVar2 = _UNK_bfc09f34;
  *_UNK_bfc09f34 = *_UNK_bfc09f34 & 0xffffff7f | 0x80;
  uVar5 = _UNK_bfc09f40;
  *puVar2 = *puVar2 & 0xfffffffc | (uint)*_UNK_bfc09f38;
  puVar3 = _UNK_bfc09f3c;
  *_UNK_bfc09f3c = *_UNK_bfc09f3c & uVar5 | _UNK_bfc09f44;
  *puVar3 = *puVar3 & _UNK_bfc09f48 | _UNK_bfc09f4c;
  uVar5 = *puVar2 & 0xfffffeff;
  if ((bVar1 & 0x20) != 0) {
    uVar5 = uVar5 | 0x100;
  }
  *puVar2 = uVar5;
  *puVar3 = *puVar3 & _UNK_bfc09f50 | _UNK_bfc09f54;
  func_0xbf1c90cc();
  iVar4 = func_0xbf1c9024(0x37,0,1);
  if (iVar4 != 0) {
    func_0xbf1c90cc();
    iVar4 = func_0xbf1c9024(1,_UNK_bfc09f58,0x21);
    if (iVar4 != 0) {
      *_UNK_bfc09f5c = 0;
      return 0xffffffff;
    }
  }
  *_UNK_bfc09f5c = 0;
  return 0;
}


// ===== FUN_bfc0a578 @ bfc0a578

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

undefined4 FUN_bfc0a578(undefined4 param_1,uint param_2)

{
  code *pcVar1;
  int iVar2;
  int iVar3;
  int iVar4;
  undefined4 *puVar5;
  
  param_2 = param_2 & 0xff;
  iVar2 = *_UNK_bfc0a66c;
  (*_UNK_bfc0a670)(0,0,0);
  if (*(int *)(param_2 * 0xc + _UNK_bfc0a674 + 8) == 0) {
    iVar3 = (*_UNK_bfc0a678)(param_1,0,0);
    if (iVar3 != 0) {
      (*_UNK_bfc0a67c)(iVar3,0,0);
      (*_UNK_bfc0a680)(0,0,0);
      (*_UNK_bfc0a670)(0,0,0);
      (*_UNK_bfc0a684)(iVar3,iVar2 + 0x200,0x44);
      iVar4 = (*_UNK_bfc0a68c)(iVar2 + 0x204,_UNK_bfc0a688,4);
      if (iVar4 == 0) {
        iVar4 = 3;
        puVar5 = (undefined4 *)(iVar2 + 0x208);
        do {
          if (puVar5[1] != 0) {
            (*_UNK_bfc0a67c)(iVar3,0,*puVar5);
            (*_UNK_bfc0a684)(iVar3,puVar5[2],puVar5[1]);
          }
          iVar4 = iVar4 + -1;
          puVar5 = puVar5 + 3;
        } while (-1 < iVar4);
        (*_UNK_bfc0a690)(*(undefined4 *)(iVar2 + 0x23c),0,*(undefined4 *)(iVar2 + 0x238));
        *(undefined4 *)(param_2 * 0xc + _UNK_bfc0a674 + 8) = *(undefined4 *)(iVar3 + 4);
        *(undefined4 *)(param_2 * 8 + _UNK_bfc0a694 + 4) = *(undefined4 *)(iVar2 + 0x240);
        (*_UNK_bfc0a698)(iVar3,0,0);
        pcVar1 = _UNK_bfc0a680;
        *_UNK_bfc0a69c = 0;
        (*pcVar1)(0,0,0);
        return 0;
      }
    }
  }
  (*_UNK_bfc0a680)(0,0,0);
  return 0xffffffff;
}


// ===== FUN_bfc0c300 @ bfc0c300

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

uint FUN_bfc0c300(uint param_1,int param_2,uint *param_3)

{
  longlong lVar1;
  uint *puVar2;
  ushort *puVar3;
  code *pcVar4;
  undefined4 *puVar5;
  undefined4 uVar6;
  int iVar7;
  byte *pbVar8;
  uint uVar9;
  byte *pbVar10;
  uint uVar11;
  uint uVar12;
  uint uVar13;
  uint uVar14;
  undefined4 *puVar15;
  byte bVar16;
  uint in_lo;
  undefined1 auStack_40 [8];
  uint uStack_38;
  uint uStack_34;
  undefined4 uStack_30;
  uint uStack_2c;
  undefined4 uStack_28;
  uint uStack_24;
  uint uStack_20;
  uint uStack_1c;
  uint uStack_18;
  
  (*_UNK_bfc0c758)(0,0,0);
  uStack_2c = *_UNK_bfc0c75c;
  if (param_2 == 4) {
    *param_3 = *_UNK_bfc0c760 >> 3 & 1;
    uStack_20 = 0;
    goto LAB_bfc0c632;
  }
  uStack_20 = (uint)(param_2 == 3);
  if (uStack_20 == 0) {
    if ((uStack_2c & 0xffff) == param_1) goto LAB_bfc0c632;
    uVar12 = uStack_2c >> 0x10 & 0xff;
    if ((*_UNK_bfc0c760 & 8) != 0) {
      param_1 = uStack_2c;
    }
    uVar11 = param_1 & 0xff;
    uVar9 = param_1 >> 8 & 0xff;
    bVar16 = *_UNK_bfc0c764;
    *_UNK_bfc0c75c = uVar9 << 8 | uVar11 | uVar12 << 0x10;
    uVar14 = (uint)bVar16;
    if (uVar11 < 5) goto LAB_bfc0c36a;
LAB_bfc0c610:
    if (0x68 < uVar11) {
      uVar11 = 0x68;
    }
  }
  else {
    if (uStack_2c >> 0x10 == param_1) goto LAB_bfc0c632;
    uVar12 = param_1 & 0xff;
    uVar9 = uStack_2c >> 8 & 0xff;
    uVar11 = uStack_2c & 0xff;
    bVar16 = *_UNK_bfc0c764;
    *_UNK_bfc0c75c = uVar9 << 8 | uVar11 | uVar12 << 0x10;
    uVar14 = (uint)bVar16;
    if (4 < uVar11) goto LAB_bfc0c610;
LAB_bfc0c36a:
    uVar11 = 5;
  }
  if (0xaf < uVar9) {
    uVar9 = 0xaf;
  }
  if (100 < uVar12) {
    uVar12 = 100;
  }
  uVar13 = uVar11;
  if (uVar11 < uVar9) {
    uVar13 = uVar9;
  }
  if (uVar13 < uVar12) {
    uVar13 = uVar12;
  }
  pbVar8 = _UNK_bfc0c76c;
  pbVar10 = _UNK_bfc0c768;
  do {
    uVar9 = (uint)*pbVar10;
    if ((uVar9 != 0) && (uVar9 < uVar13)) {
      uVar13 = uVar9;
    }
    uVar9 = (uint)*pbVar8;
    if ((uVar9 != 0) && (uVar13 < uVar9)) {
      uVar13 = uVar9;
    }
    pbVar8 = pbVar8 + 1;
    pbVar10 = pbVar10 + 1;
  } while (pbVar8 < _UNK_bfc0c76c + 4);
  uVar9 = (uint)bVar16;
  if ((uVar13 <= uVar9) && (uVar13 = uVar9, uVar12 > uVar14)) {
    uVar13 = uVar9 + 1;
  }
  if (bVar16 < uVar13) {
    if (bVar16 == 0x28) {
      lVar1 = (ulonglong)(uVar13 + 4) * (ulonglong)_UNK_bfc0c770;
      in_lo = (uint)lVar1;
      uStack_18 = (uint)((ulonglong)lVar1 >> 0x22);
      if (uStack_18 < 6) {
        uStack_18 = 6;
      }
      uVar13 = uStack_18 * 5;
    }
    else {
      lVar1 = (ulonglong)(uVar13 * 2 + 0xc) * (ulonglong)_UNK_bfc0c774;
      in_lo = (uint)lVar1;
      uStack_18 = (uint)((ulonglong)lVar1 >> 0x22);
      if (uStack_18 < 6) {
        uStack_18 = 6;
      }
      uVar13 = uStack_18 * 6 + (uStack_18 + 1 >> 1);
    }
    uStack_18 = uStack_18 | 0x80;
    uVar9 = 2;
  }
  else {
    uVar9 = 3;
    uStack_18 = 0;
  }
  uStack_38 = 0;
  uStack_24 = uVar13;
  if (uVar11 < uVar13) {
    uStack_38 = (uint)(uVar11 <= uVar13 >> 1);
    if (uVar11 <= uVar13 >> 2) {
      uStack_38 = uStack_38 + 1;
    }
    if (uVar13 >> 3 < uVar11) {
      uStack_24 = uVar13 >> uStack_38;
      if (0x68 < uStack_24) {
        uStack_38 = uStack_38 + 1;
        uStack_24 = uVar13 >> uStack_38;
      }
    }
    else {
      uStack_38 = uStack_38 + 1;
      uStack_24 = uVar13 >> uStack_38;
    }
  }
  if ((uVar12 <= uVar14) || (uVar13 <= bVar16)) {
    uStack_34 = (uint)(uVar12 < uVar14 >> 1);
    if (uStack_34 + 1 != 0) {
      in_lo = uVar14 / (uStack_34 + 1);
    }
    uStack_1c = in_lo;
  }
  else {
    uStack_34 = (uint)(uVar12 <= uVar13 >> 1);
    uStack_1c = (uint)((ulonglong)uVar13 * (ulonglong)_UNK_bfc0c778);
    if (uVar12 <= (uint)((ulonglong)uVar13 * (ulonglong)_UNK_bfc0c778 >> 0x21)) {
      uStack_34 = uStack_34 + 1;
    }
    if ((uVar12 <= uVar13 >> 2) || (100 < uVar13 >> uStack_34)) {
      uStack_34 = uStack_34 + 1;
    }
    if (uStack_34 + 1 != 0) {
      uStack_1c = uVar13 / (uStack_34 + 1);
    }
    uStack_34 = uStack_34 | 0x10;
  }
  uVar9 = uStack_38 << 4 | uVar9;
  if (0x34 < uStack_24 >> 1) {
    uVar9 = uVar9 | 0x100;
  }
  if (((*_UNK_bfc0c77c == uVar13) && ((*_UNK_bfc0c780 & 0x133) == uVar9)) &&
     ((*_UNK_bfc0c784 & 0x13) == uStack_34)) goto LAB_bfc0c632;
  uStack_30 = (*_FUN_bfc0c788)(0,0,0);
  (*_UNK_bfc0c78c)(auStack_40,0,0);
  uStack_28 = (*_UNK_bfc0c790)(0,0,0);
  puVar2 = _UNK_bfc0c780;
  *_UNK_bfc0c780 = *_UNK_bfc0c780 & 0x3003;
  iVar7 = 0x1f;
  do {
    iVar7 = iVar7 + -1;
  } while (-1 < iVar7);
  *puVar2 = *puVar2 & 0xfffffffc | 3;
  pbVar8 = _UNK_bfc0c760;
  iVar7 = 0x1f;
  do {
    iVar7 = iVar7 + -1;
  } while (-1 < iVar7);
  *_UNK_bfc0c784 = 0;
  bVar16 = *pbVar8;
  *_UNK_bfc0c794 = (ushort)*_UNK_bfc0c764;
  if ((bVar16 & 1) == 0) {
    *_UNK_bfc0c798 = *_UNK_bfc0c798 & 0xfffffff0 | 8;
    (*_UNK_bfc0c79c)(10,0,0);
  }
  if (*_UNK_bfc0c77c < uVar13) {
    if (*_UNK_bfc0c764 < uVar13) {
      *_UNK_bfc0c7a0 = uStack_18;
      goto LAB_bfc0c6de;
    }
  }
  else if ((uVar13 < *_UNK_bfc0c77c) && (*_UNK_bfc0c764 < uVar13)) {
    *_UNK_bfc0c7a0 = uStack_18;
LAB_bfc0c6de:
    (*_UNK_bfc0c79c)(100,0,0);
  }
  pcVar4 = _UNK_bfc0c79c;
  puVar2 = _UNK_bfc0c780;
  if (*_UNK_bfc0c7a4 < 0) {
    *_UNK_bfc0c7a8 = *_UNK_bfc0c7a8 | _UNK_bfc0c7ac;
    (*pcVar4)(0x32,0,0);
    puVar2 = _UNK_bfc0c780;
    uVar12 = *_UNK_bfc0c780;
    iVar7 = 0x31;
    *_UNK_bfc0c780 = uVar12 & 0x3003 | uVar9 & 0xfffffffc;
    do {
      iVar7 = iVar7 + -1;
    } while (-1 < iVar7);
    *puVar2 = uVar12 & 0x3000 | uVar9 & 0xfffffffc | uVar9 & 3;
    puVar2 = _UNK_bfc0c7a8;
    iVar7 = 0x31;
    do {
      iVar7 = iVar7 + -1;
    } while (-1 < iVar7);
    *_UNK_bfc0c7a8 = *_UNK_bfc0c7a8 & 0xfffffeff;
    iVar7 = 99;
    do {
      iVar7 = iVar7 + -1;
    } while (-1 < iVar7);
    *puVar2 = *puVar2 & _UNK_bfc0c7b0;
  }
  else {
    uVar12 = *_UNK_bfc0c780;
    iVar7 = 0x31;
    *_UNK_bfc0c780 = uVar12 & 0x3003 | uVar9 & 0xfffffffc;
    do {
      iVar7 = iVar7 + -1;
    } while (-1 < iVar7);
    *puVar2 = uVar12 & 0x3000 | uVar9 & 0xfffffffc | uVar9 & 3;
    iVar7 = 0x31;
    do {
      iVar7 = iVar7 + -1;
    } while (-1 < iVar7);
  }
  pbVar10 = _UNK_bfc0c7b4;
  pbVar8 = _UNK_bfc0c77c;
  *_UNK_bfc0c784 = uStack_34;
  *pbVar10 = (byte)uVar13;
  *pbVar8 = (byte)uVar13;
  puVar15 = _UNK_bfc0c7bc;
  puVar3 = _UNK_bfc0c794;
  *_UNK_bfc0c7b8 = (undefined1)uStack_38;
  *puVar3 = (ushort)(byte)uStack_24;
  iVar7 = 3;
  do {
    if ((code *)*puVar15 != (code *)0x0) {
      (*(code *)*puVar15)(*_UNK_bfc0c77c);
    }
    pcVar4 = _UNK_bfc0c7cc;
    uVar6 = _UNK_bfc0c7c8;
    puVar5 = _UNK_bfc0c7c4;
    iVar7 = iVar7 + -1;
    puVar15 = puVar15 + 1;
  } while (-1 < iVar7);
  *_UNK_bfc0c7c0 = uStack_28;
  *puVar5 = uStack_28;
  (*pcVar4)(uVar6,auStack_40,8);
  (*pcVar4)(_UNK_bfc0c7d0,auStack_40,8);
  (*_FUN_bfc0c7d4)(uStack_30,0,0);
LAB_bfc0c632:
  uVar12 = uStack_2c >> 0x10;
  if (uStack_20 == 0) {
    uVar12 = uStack_2c & 0xffff;
  }
  (*_UNK_bfc0c7d8)(0,0,0);
  return uVar12;
}


