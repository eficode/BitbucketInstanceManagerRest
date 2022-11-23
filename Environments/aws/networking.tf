resource "aws_vpc" "base-stack" {

  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-vpc"
  }


}




resource "aws_internet_gateway" "base-stack" {

  vpc_id = aws_vpc.base-stack.id


  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-gw"
  }


}
resource "aws_eip" "nat_eip" {

  vpc        = true
  depends_on = [aws_internet_gateway.base-stack]
  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-eip-${count.index}"
  }

  count = length(data.aws_availability_zones.available.names)

}


resource "aws_subnet" "base-stack-private-subnet" {


  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-private-subnet-${count.index}"
  }

  availability_zone       = data.aws_availability_zones.available.names[count.index]
  cidr_block              = "10.0.${count.index}.0/24"
  vpc_id                  = aws_vpc.base-stack.id
  map_public_ip_on_launch = false

  count = length(data.aws_availability_zones.available.names)


}

resource "aws_subnet" "base-stack-public-subnet" {


  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-public-subnet-${count.index}"
  }

  availability_zone = data.aws_availability_zones.available.names[count.index]
  cidr_block        = "10.0.${100 + count.index}.0/24"
  vpc_id            = aws_vpc.base-stack.id

  count = length(data.aws_availability_zones.available.names)


}

resource "aws_nat_gateway" "outbound-nat" {

  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-outbount-nat-${count.index}"
  }
  allocation_id = aws_eip.nat_eip[count.index].id
  subnet_id     = aws_subnet.base-stack-public-subnet[count.index].id

  count = length(data.aws_availability_zones.available.names)


}
resource "aws_route_table" "private-route-tb" {

  vpc_id = aws_vpc.base-stack.id

  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-private-routetable-${count.index}"
  }

  count = length(data.aws_availability_zones.available.names)

}

resource "aws_route_table" "public-route-tb" {

  vpc_id = aws_vpc.base-stack.id

  tags = {
    Name = "${var.tags.useCase}-${var.tags.owner}-private-routetable-${count.index}"
  }

  count = length(data.aws_availability_zones.available.names)

}

resource "aws_route" "route-to-world" {

  route_table_id         = aws_route_table.public-route-tb[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.base-stack.id


  count = length(data.aws_availability_zones.available.names)

}

resource "aws_route" "route-to-nat" {

  route_table_id         = aws_route_table.private-route-tb[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id      = aws_nat_gateway.outbound-nat[count.index].id

  count = length(data.aws_availability_zones.available.names)

}

resource "aws_route_table_association" "route-public" {

  subnet_id      = aws_subnet.base-stack-public-subnet[count.index].id
  route_table_id = aws_route_table.public-route-tb[count.index].id

  count = length(data.aws_availability_zones.available.names)


}

resource "aws_route_table_association" "route-private" {

  subnet_id      = aws_subnet.base-stack-private-subnet[count.index].id
  route_table_id = aws_route_table.private-route-tb[count.index].id

  count = length(data.aws_availability_zones.available.names)

}





resource "aws_security_group_rule" "ingress_rules" {
  count = length(var.ingress_rules_from_trusted)

  type              = "ingress"
  from_port         = var.ingress_rules_from_trusted[count.index].port
  to_port           = var.ingress_rules_from_trusted[count.index].port
  protocol          = upper(var.ingress_rules_from_trusted[count.index].protocol)
  cidr_blocks       = var.trusted-external-ips
  description       = var.ingress_rules_from_trusted[count.index].description
  security_group_id = aws_security_group.main-sg.id
}


resource "aws_security_group" "main-sg" {

  name   = "${var.tags.useCase}-${var.tags.owner}-sg"
  vpc_id = aws_vpc.base-stack.id


  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

}
