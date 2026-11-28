// ===== FUN_bfc28770 @ bfc28770

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

int FUN_bfc28770(void)

{
  uint *puVar1;
  uint *puVar2;
  uint uVar3;
  uint uVar4;
  uint uVar5;
  code *pcVar6;
  undefined1 *puVar7;
  int iVar8;
  uint uVar9;
  uint uVar10;
  uint uVar11;
  uint uVar12;
  
  uVar5 = _UNK_bfc28878;
  uVar4 = _UNK_bfc28870;
  uVar3 = _UNK_bfc28868;
  puVar2 = _UNK_bfc2885c;
  puVar1 = _UNK_bfc28858;
  uVar11 = *_UNK_bfc28850 & _UNK_bfc28854;
  uVar12 = *_UNK_bfc28858 & _UNK_bfc28854;
  uVar9 = *_UNK_bfc2885c & _UNK_bfc28860;
  uVar10 = *_UNK_bfc28864 & _UNK_bfc28868;
  if ((*_UNK_bfc2886c & 1) == 0) {
    *_UNK_bfc28850 = *_UNK_bfc28850 & _UNK_bfc28878;
    *puVar1 = *puVar1 & uVar5;
    puVar1 = _UNK_bfc28864;
    *puVar2 = *puVar2 & _UNK_bfc2887c;
    pcVar6 = _UNK_bfc28880;
    *puVar1 = *puVar1 | *puVar1 | uVar3;
    iVar8 = (*pcVar6)(0xc,0,3);
  }
  else {
    *_UNK_bfc28850 = *_UNK_bfc28850 & _UNK_bfc28870;
    *puVar1 = *puVar1 & uVar4;
    puVar1 = _UNK_bfc28864;
    *puVar2 = *puVar2 & _UNK_bfc28874;
    pcVar6 = _UNK_bfc28880;
    *puVar1 = *puVar1 | *puVar1 | uVar3;
    iVar8 = (*pcVar6)(0xc,0,3);
  }
  if (iVar8 == 0) {
    iVar8 = (*_UNK_bfc28884)(0,3);
  }
  uVar4 = _UNK_bfc28888;
  puVar1 = _UNK_bfc28858;
  *_UNK_bfc28850 = *_UNK_bfc28850 & _UNK_bfc28888 | uVar11;
  uVar3 = _UNK_bfc28874;
  *puVar1 = *puVar1 & uVar4 | uVar12;
  puVar1 = _UNK_bfc28864;
  *_UNK_bfc2885c = *_UNK_bfc2885c & uVar3 | uVar9;
  puVar7 = _UNK_bfc28890;
  *_UNK_bfc28864 = *puVar1 & _UNK_bfc2888c | uVar10;
  *puVar7 = 0;
  return iVar8;
}


// ===== FUN_bfc28d60 @ bfc28d60

/* WARNING: Globals starting with '_' overlap smaller symbols at the same address */

int FUN_bfc28d60(void)

{
  undefined1 *puVar1;
  undefined4 *puVar2;
  undefined4 *puVar3;
  byte *pbVar4;
  uint *puVar5;
  uint *puVar6;
  uint *puVar7;
  uint uVar8;
  ushort *puVar9;
  uint uVar10;
  uint uVar11;
  int iVar12;
  uint uVar13;
  uint uVar14;
  uint uVar15;
  uint uVar16;
  
  func_0xbfc288c8(1);
  puVar3 = _UNK_bfc28eb8;
  puVar2 = _UNK_bfc28eb4;
  puVar1 = _UNK_bfc28eb0;
  *_UNK_bfc28eac = 2;
  *puVar1 = 1;
  pbVar4 = _UNK_bfc28ebc;
  *puVar2 = 0xffffffff;
  *puVar3 = 0;
  *pbVar4 = *pbVar4 & 0xfe;
  FUN_bfc28d20();
  uVar11 = _UNK_bfc28ee8;
  uVar13 = _UNK_bfc28ee0;
  uVar8 = _UNK_bfc28ed8;
  puVar7 = _UNK_bfc28ed4;
  puVar6 = _UNK_bfc28ecc;
  puVar5 = _UNK_bfc28ec8;
  uVar15 = *_UNK_bfc28ec0 & _UNK_bfc28ec4;
  uVar16 = *_UNK_bfc28ec8 & _UNK_bfc28ec4;
  uVar14 = *_UNK_bfc28ecc & _UNK_bfc28ed0;
  uVar10 = *_UNK_bfc28ed4 & _UNK_bfc28ed8;
  if ((*_UNK_bfc28edc & 1) == 0) {
    *_UNK_bfc28ec0 = *_UNK_bfc28ec0 & _UNK_bfc28ee8;
    *puVar5 = *puVar5 & uVar11;
    uVar11 = *puVar6;
    uVar13 = _UNK_bfc28eec;
  }
  else {
    *_UNK_bfc28ec0 = *_UNK_bfc28ec0 & _UNK_bfc28ee0;
    *puVar5 = *puVar5 & uVar13;
    uVar11 = *puVar6;
    uVar13 = _UNK_bfc28ee4;
  }
  *puVar6 = uVar11 & uVar13;
  *puVar7 = *puVar7 | *puVar7 | uVar8;
  FUN_bfc28f50();
  iVar12 = FUN_bfc2893c();
  if (iVar12 != 0) {
    (*_UNK_bfc28ef0)(200,0,0,0x1d);
    iVar12 = FUN_bfc2893c();
    if (iVar12 != 0) goto LAB_bfc28e12;
  }
  iVar12 = FUN_bfc29200();
  if (iVar12 == 0) {
    (*_UNK_bfc28ef4)(0);
    puVar9 = _UNK_bfc28efc;
    *(undefined1 *)(_UNK_bfc28ef8 + 2) = 0x6b;
    iVar12 = (*_UNK_bfc28f00)(0xd,(uint)*puVar9 << 0x10,1);
    if ((iVar12 == 0) && (iVar12 = FUN_bfc28cc4((*_UNK_bfc28edc & 1) << 2), iVar12 == 0)) {
      iVar12 = FUN_bfc290d8();
    }
  }
LAB_bfc28e12:
  uVar13 = _UNK_bfc28f04;
  puVar5 = _UNK_bfc28ec8;
  *_UNK_bfc28ec0 = *_UNK_bfc28ec0 & _UNK_bfc28f04 | uVar15;
  uVar8 = _UNK_bfc28ee4;
  *puVar5 = *puVar5 & uVar13 | uVar16;
  uVar13 = _UNK_bfc28f08;
  *_UNK_bfc28ecc = *_UNK_bfc28ecc & uVar8 | uVar14;
  puVar2 = _UNK_bfc28eb8;
  *_UNK_bfc28ed4 = *_UNK_bfc28ed4 & uVar13 | uVar10;
  pbVar4 = _UNK_bfc28ebc;
  *puVar2 = 1;
  *pbVar4 = *pbVar4 | 2;
  return iVar12;
}


